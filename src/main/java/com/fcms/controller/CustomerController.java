package com.fcms.controller;

import com.fcms.model.Customer;
import com.fcms.model.CustomerStatus;
import com.fcms.model.FinanceType;
import com.fcms.service.AuthService;
import com.fcms.service.CustomerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final AuthService authService;

    public CustomerController(CustomerService customerService, AuthService authService) {
        this.customerService = customerService;
        this.authService = authService;
    }

    private void requireAdmin(String authHeader) {
        String token = authHeader != null && authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        if (!authService.isAdmin(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required for this action");
        }
    }

    @GetMapping
    public List<Customer> getAll(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) CustomerStatus status,
            @RequestParam(required = false) FinanceType financeType,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) Boolean overdue) {
        if (q == null && status == null && financeType == null && paymentStatus == null && overdue == null) {
            return customerService.getAll();
        }
        return customerService.search(q, status, financeType, paymentStatus, overdue);
    }

    @GetMapping("/search")
    public List<Customer> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) CustomerStatus status,
            @RequestParam(required = false) FinanceType financeType,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) Boolean overdue) {
        return customerService.search(q, status, financeType, paymentStatus, overdue);
    }

    @GetMapping("/due-today")
    public List<Customer> dueToday() {
        return customerService.getDueToday();
    }

    /** Quick Collection rows for a given date (defaults to today, IST) — due/overdue + already-marked loans. */
    @GetMapping("/quick-collection")
    public List<com.fcms.dto.QuickCollectionRow> quickCollection(
            @RequestParam(required = false) java.time.LocalDate date) {
        java.time.LocalDate d = date != null ? date : java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        return customerService.getQuickCollection(d);
    }

    /** Other loan accounts belonging to the same person as customer {id} (multiple-loans-per-person support). */
    @GetMapping("/{id}/other-loans")
    public List<Customer> otherLoans(@PathVariable Long id) {
        Customer c = customerService.getById(id).orElse(null);
        if (c == null || c.getGroupKey() == null) return List.of();
        return customerService.getLoansForGroup(c.getGroupKey()).stream()
                .filter(o -> !o.getId().equals(id))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Customer> getById(@PathVariable Long id) {
        return customerService.getById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public Customer create(@RequestBody Customer customer) {
        return customerService.create(customer);
    }

    @PutMapping("/{id}")
    public Customer update(@PathVariable Long id, @RequestBody Customer customer,
                            @RequestParam(defaultValue = "system") String editedBy,
                            @RequestParam(defaultValue = "") String reason,
                            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        requireAdmin(authHeader);
        return customerService.update(id, customer, editedBy, reason);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                        @RequestHeader(value = "Authorization", required = false) String authHeader) {
        requireAdmin(authHeader);
        customerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Marks a fully-collected loan as Closed, removing it from active/Running totals. */
    @PutMapping("/{id}/close")
    public Customer close(@PathVariable Long id,
                           @RequestParam(defaultValue = "system") String editedBy,
                           @RequestParam(defaultValue = "Account closed after full collection") String reason,
                           @RequestHeader(value = "Authorization", required = false) String authHeader) {
        requireAdmin(authHeader);
        try {
            return customerService.closeAccount(id, editedBy, reason);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
