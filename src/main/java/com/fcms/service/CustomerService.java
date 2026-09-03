package com.fcms.service;

import com.fcms.dto.QuickCollectionRow;
import com.fcms.model.Customer;
import com.fcms.model.CustomerStatus;
import com.fcms.model.FinanceType;
import com.fcms.model.Payment;
import com.fcms.repository.CustomerRepository;
import com.fcms.repository.PaymentRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final PaymentRepository paymentRepository;
    private final AuditService auditService;

    public CustomerService(CustomerRepository customerRepository, PaymentRepository paymentRepository, AuditService auditService) {
        this.customerRepository = customerRepository;
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
    }

    public List<Customer> getAll() {
        return customerRepository.findAll();
    }

    public Optional<Customer> getById(Long id) {
        return customerRepository.findById(id);
    }

    public Customer create(Customer c) {
        if (c.getGroupKey() == null || c.getGroupKey().isBlank()) {
            c.setGroupKey(slug(c.getName()));
        } else {
            c.setGroupKey(slug(c.getGroupKey()));
        }
        recomputeDerived(c);
        c.setCreatedAt(LocalDate.now().atStartOfDay());
        c.setPaidInstallments(c.getPaidInstallments() == null ? 0 : c.getPaidInstallments());
        c.setTotalPaid(c.getTotalPaid() == null ? 0.0 : c.getTotalPaid());
        recomputeDerived(c);
        if (c.getStatus() == null) c.setStatus(CustomerStatus.Running);
        return customerRepository.save(c);
    }

    /**
     * Normalizes a name/group label into a stable lookup key (lowercase, trimmed,
     * spaces collapsed to single hyphens) so "Ranjith Kumar" and "ranjith  kumar" group together.
     */
    public static String slug(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    }

    /** All loan accounts (including this one) that belong to the same real-world person. */
    public List<Customer> getLoansForGroup(String groupKey) {
        if (groupKey == null || groupKey.isBlank()) return List.of();
        return customerRepository.findAllByGroupKeyOrderByStartDateAsc(groupKey);
    }

    public Customer update(Long id, Customer updated, String editedBy, String reason) {
        Customer existing = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + id));

        auditField(existing, updated, "name", existing.getName(), updated.getName(), editedBy, reason);
        auditField(existing, updated, "mobile", existing.getMobile(), updated.getMobile(), editedBy, reason);
        auditField(existing, updated, "financeAmount", existing.getFinanceAmount(), updated.getFinanceAmount(), editedBy, reason);
        auditField(existing, updated, "interest", existing.getInterest(), updated.getInterest(), editedBy, reason);
        auditField(existing, updated, "installmentAmount", existing.getInstallmentAmount(), updated.getInstallmentAmount(), editedBy, reason);
        auditField(existing, updated, "totalInstallments", existing.getTotalInstallments(), updated.getTotalInstallments(), editedBy, reason);
        auditField(existing, updated, "totalPaid", existing.getTotalPaid(), updated.getTotalPaid(), editedBy, reason);
        auditField(existing, updated, "paidInstallments", existing.getPaidInstallments(), updated.getPaidInstallments(), editedBy, reason);
        auditField(existing, updated, "startDate", existing.getStartDate(), updated.getStartDate(), editedBy, reason);
        auditField(existing, updated, "status", existing.getStatus(), updated.getStatus(), editedBy, reason);

        existing.setName(updated.getName());
        existing.setMobile(updated.getMobile());
        existing.setAlternateMobile(updated.getAlternateMobile());
        existing.setAddress(updated.getAddress());
        if (updated.getGroupKey() != null && !updated.getGroupKey().isBlank()) {
            existing.setGroupKey(slug(updated.getGroupKey()));
        } else if (existing.getGroupKey() == null || existing.getGroupKey().isBlank()) {
            existing.setGroupKey(slug(existing.getName()));
        }
        existing.setFinanceAmount(updated.getFinanceAmount());
        existing.setInterest(updated.getInterest());
        existing.setStartDate(updated.getStartDate());
        existing.setFinanceType(updated.getFinanceType());
        existing.setCollectionDay(updated.getCollectionDay());
        existing.setInstallmentAmount(updated.getInstallmentAmount());
        existing.setTotalInstallments(updated.getTotalInstallments());
        // Total Paid / Installments Paid are normally only ever changed by recording payments,
        // but an admin can correct them directly here too (e.g. fixing a data-entry mistake) —
        // only applied when the caller actually sent a value, so a payload that omits them
        // (or an older client) never silently zeroes out real collection history.
        if (updated.getTotalPaid() != null) existing.setTotalPaid(updated.getTotalPaid());
        if (updated.getPaidInstallments() != null) existing.setPaidInstallments(updated.getPaidInstallments());
        if (updated.getStatus() != null) existing.setStatus(updated.getStatus());

        recomputeDerived(existing);
        return customerRepository.save(existing);
    }

    private void auditField(Customer existing, Customer updated, String field, Object oldV, Object newV, String editedBy, String reason) {
        if (newV != null && !newV.equals(oldV)) {
            auditService.log("Customer", existing.getId(), existing.getName(), field, oldV, newV, editedBy, reason);
        }
    }

    public void delete(Long id) {
        customerRepository.deleteById(id);
    }

    /**
     * Marks a fully-collected (Completed) loan as Closed. Closed loans are excluded from
     * "Running" counts/totals everywhere in the app (dashboard, Quick Collection, overdue
     * lists) — this is the final step once every installment has actually been collected.
     */
    public Customer closeAccount(Long id, String editedBy, String reason) {
        Customer c = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + id));
        if (c.getPendingAmount() != null && c.getPendingAmount() > 0) {
            throw new IllegalStateException("Cannot close this account — it still has a pending balance of " + c.getPendingAmount());
        }
        auditService.log("Customer", c.getId(), c.getName(), "status", c.getStatus(), CustomerStatus.Closed, editedBy, reason);
        c.setStatus(CustomerStatus.Closed);
        return customerRepository.save(c);
    }

    public void recomputeDerived(Customer c) {
        // Every loan defaults to a 100-installment schedule (e.g. Rs. 1,00,000 => Rs. 1,000/day
        // for 100 days) unless the caller explicitly set a different totalInstallments.
        if (c.getTotalInstallments() == null || c.getTotalInstallments() <= 0) {
            c.setTotalInstallments(100);
        }

        double financeAmount = c.getFinanceAmount() == null ? 0 : c.getFinanceAmount();
        double interest = c.getInterest() == null ? 0 : c.getInterest();
        double totalAmount = financeAmount + (financeAmount * interest / 100.0);
        c.setTotalAmount(totalAmount);

        if (c.getInstallmentAmount() == null || c.getInstallmentAmount() <= 0) {
            c.setInstallmentAmount(Math.round((totalAmount / c.getTotalInstallments()) * 100.0) / 100.0);
        }

        double totalPaid = c.getTotalPaid() == null ? 0 : c.getTotalPaid();
        double pending = Math.max(0, totalAmount - totalPaid);
        c.setPendingAmount(pending);
        c.setCurrentBalance(pending);

        if (c.getNextDueDate() == null && c.getStartDate() != null) {
            c.setNextDueDate(computeNextDueDate(c.getStartDate(), c.getFinanceType()));
        }

        if (c.getStartDate() != null && c.getTotalInstallments() != null) {
            c.setEndDate(computeNextDueDate(c.getStartDate(), c.getFinanceType(), c.getTotalInstallments()));
        }

        if (pending <= 0 && c.getStatus() != CustomerStatus.Closed) {
            c.setStatus(CustomerStatus.Completed);
        }
    }

    public LocalDate computeNextDueDate(LocalDate from, FinanceType type) {
        return computeNextDueDate(from, type, 1);
    }

    /**
     * Advances the given date by `periods` installment periods (days for Daily,
     * weeks for Weekly). Used for Advance payments that cover multiple installments.
     */
    public LocalDate computeNextDueDate(LocalDate from, FinanceType type, int periods) {
        if (periods < 1) periods = 1;
        if (type == FinanceType.Weekly) {
            return from.plusWeeks(periods);
        }
        return from.plusDays(periods);
    }

    /** Indian Standard Time — the day rolls over (and today's collections appear) at 12:00 AM IST, regardless of the server's own timezone. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Customers whose next payment is due today or is overdue (nextDueDate < today),
     * restricted to Running status. Used for the "Quick Collection" due-today list.
     *
     * "Today" is always computed in IST so the list refreshes at 12:00 AM India time no
     * matter where the backend server itself is hosted/deployed. Overdue loans (nextDueDate
     * before today) and loans due exactly today are both shown as soon as the day starts.
     *
     * Once a loan has ANY payment recorded today — Paid, Partial, NotPaid, or Advance — it
     * drops off this list immediately, even if (for Partial/NotPaid) nextDueDate didn't
     * advance. It only reappears after the day rolls over at 12:00 AM IST.
     */
    public List<Customer> getDueToday() {
        LocalDate today = LocalDate.now(IST);
        Set<Long> alreadyMarkedToday = paymentRepository.findByDate(today).stream()
                .map(Payment::getCustomerId)
                .collect(Collectors.toSet());
        return customerRepository.findAll().stream()
                .filter(c -> c.getStatus() == CustomerStatus.Running)
                .filter(c -> c.getNextDueDate() != null && !c.getNextDueDate().isAfter(today))
                .filter(c -> !alreadyMarkedToday.contains(c.getId()))
                .toList();
    }

    /**
     * Quick Collection rows for one date: for today, every Running loan that's due/overdue OR
     * already has a payment recorded today; for any other (past) date, every loan that has a
     * payment recorded on that date — so the same date-driven view works for live same-day
     * collection and for looking back at/editing an earlier day's collections.
     */
    public List<QuickCollectionRow> getQuickCollection(LocalDate date) {
        LocalDate today = LocalDate.now(IST);
        Map<Long, Payment> paymentsOnDate = paymentRepository.findByDate(date).stream()
                .collect(Collectors.toMap(Payment::getCustomerId, p -> p, (a, b) -> a));

        List<Customer> rows;
        if (date.equals(today)) {
            rows = customerRepository.findAll().stream()
                    .filter(c -> c.getStatus() == CustomerStatus.Running)
                    .filter(c -> paymentsOnDate.containsKey(c.getId())
                            || (c.getNextDueDate() != null && !c.getNextDueDate().isAfter(today)))
                    .toList();
        } else {
            rows = customerRepository.findAll().stream()
                    .filter(c -> paymentsOnDate.containsKey(c.getId()))
                    .toList();
        }

        return rows.stream()
                .sorted(Comparator.comparing(Customer::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> {
                    QuickCollectionRow row = new QuickCollectionRow();
                    row.setCustomerId(c.getId());
                    row.setName(c.getName());
                    row.setMobile(c.getMobile());
                    row.setGroupKey(c.getGroupKey());
                    row.setFinanceAmount(c.getFinanceAmount());
                    row.setInstallmentAmount(c.getInstallmentAmount());
                    row.setFinanceType(c.getFinanceType());
                    row.setNextDueDate(c.getNextDueDate());
                    row.setStatus(c.getStatus());
                    Payment p = paymentsOnDate.get(c.getId());
                    if (p != null) {
                        row.setPaymentId(p.getId());
                        row.setPaymentType(p.getType());
                        row.setPaymentAmount(p.getAmount());
                        row.setNotes(p.getNotes());
                        row.setCollectedBy(p.getCollectedBy());
                    }
                    return row;
                })
                .toList();
    }

    /**
     * In-memory filtered search over all customers. All provided filters are combined with AND.
     */
    public List<Customer> search(String q, CustomerStatus status, FinanceType financeType,
                                  String paymentStatus, Boolean overdue) {
        LocalDate today = LocalDate.now(IST);
        String qLower = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();

        return customerRepository.findAll().stream()
                .filter(c -> qLower == null
                        || (c.getName() != null && c.getName().toLowerCase().contains(qLower))
                        || (c.getMobile() != null && c.getMobile().toLowerCase().contains(qLower)))
                .filter(c -> status == null || c.getStatus() == status)
                .filter(c -> financeType == null || c.getFinanceType() == financeType)
                .filter(c -> {
                    // Filters by the type of the most recently recorded payment on the loan
                    // (Paid / Partial / NotPaid / Advance) — i.e. "who did we mark as X last time".
                    if (paymentStatus == null || paymentStatus.isBlank()) return true;
                    return paymentStatus.equalsIgnoreCase(c.getLastPaymentType());
                })
                .filter(c -> {
                    if (overdue == null) return true;
                    boolean isOverdue = c.getStatus() == CustomerStatus.Running
                            && c.getNextDueDate() != null && c.getNextDueDate().isBefore(today);
                    return overdue == isOverdue;
                })
                .toList();
    }
}
