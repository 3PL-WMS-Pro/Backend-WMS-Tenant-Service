package com.wmspro.tenant.billing.adjustment

import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.billing.warehousejob.api.WarehouseJobStaffAuthorization
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/movement-cost-adjustments/supplier-expenses")
class SupplierExpenseController(
    private val service: SupplierExpenseService,
    private val sync: SupplierExpenseSyncService,
    private val authorization: WarehouseJobStaffAuthorization
) {
    @GetMapping
    fun list(@RequestParam attachedToId: String, request: HttpServletRequest): ResponseEntity<ApiResponse<List<SupplierExpense>>> {
        authorization.require(request, "canViewWarehouseJobs")
        return ResponseEntity.ok(ApiResponse.success(service.list(attachedToId)))
    }

    @PostMapping
    fun create(@RequestBody body: SaveSupplierExpenseRequest, request: HttpServletRequest) = save(body, request, false)

    @PutMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody body: SaveSupplierExpenseRequest, request: HttpServletRequest): ResponseEntity<ApiResponse<SupplierExpense>> {
        require(id == body.expenseId) { "Expense ID mismatch" }
        return save(body, request, true)
    }

    private fun save(body: SaveSupplierExpenseRequest, request: HttpServletRequest, editing: Boolean): ResponseEntity<ApiResponse<SupplierExpense>> {
        authorization.require(request, "canManageSupplierExpenses")
        val token = request.getHeader("Authorization").orEmpty()
        service.save(body, request.getHeader("X-User-Email").orEmpty(), token, editing)
        return ResponseEntity.ok(ApiResponse.success(sync.sync(body.expenseId, token), "Supplier expense saved"))
    }

    @PostMapping("/{id}/sync")
    fun retry(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<ApiResponse<SupplierExpense>> {
        authorization.require(request, "canManageSupplierExpenses")
        return ResponseEntity.ok(ApiResponse.success(sync.sync(id, request.getHeader("Authorization").orEmpty())))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String, @RequestParam revision: Long, request: HttpServletRequest): ResponseEntity<ApiResponse<Unit>> {
        authorization.require(request, "canManageSupplierExpenses")
        service.delete(id, revision)
        return ResponseEntity.ok(ApiResponse.success(Unit))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(error: IllegalArgumentException): ResponseEntity<ApiResponse<Unit>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(error.message ?: "Invalid supplier expense"))
}
