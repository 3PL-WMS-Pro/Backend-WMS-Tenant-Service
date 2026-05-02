package com.wmspro.tenant.billing.adjustment

import com.wmspro.common.dto.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RestController
@RequestMapping("/api/v1/movement-cost-adjustments")
class MovementCostAdjustmentController(
    private val service: MovementCostAdjustmentService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun listForRecord(
        @RequestParam attachedToId: String
    ): ResponseEntity<ApiResponse<List<MovementCostAdjustmentResponse>>> {
        val list = service.findForRecord(attachedToId).map { it.toResponse() }
        return ResponseEntity.ok(ApiResponse.success(list, "Movement cost adjustments retrieved"))
    }

    @GetMapping("/customer/{customerId}")
    fun listForCustomer(
        @PathVariable customerId: Long,
        @RequestParam from: String,
        @RequestParam to: String
    ): ResponseEntity<ApiResponse<List<MovementCostAdjustmentResponse>>> {
        val fromInstant = LocalDate.parse(from).atStartOfDay().toInstant(ZoneOffset.UTC)
        val toInstant = LocalDate.parse(to).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val list = service.findForCustomer(customerId, fromInstant, toInstant).map { it.toResponse() }
        return ResponseEntity.ok(ApiResponse.success(list, "Movement cost adjustments retrieved"))
    }

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateMovementCostAdjustmentRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<MovementCostAdjustmentResponse>> {
        val userEmail = httpRequest.getHeader("X-User-Email") ?: "unknown"
        return try {
            val saved = service.create(request, userEmail)
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(saved.toResponse(), "Movement cost adjustment created")
            )
        } catch (e: IllegalArgumentException) {
            logger.warn("Validation error creating movement cost adjustment: {}", e.message)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.message ?: "Validation error"))
        } catch (e: Exception) {
            logger.error("Error creating movement cost adjustment", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    @PutMapping("/{adjustmentId}")
    fun update(
        @PathVariable adjustmentId: String,
        @Valid @RequestBody request: UpdateMovementCostAdjustmentRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<MovementCostAdjustmentResponse>> {
        val userEmail = httpRequest.getHeader("X-User-Email") ?: "unknown"
        return try {
            val saved = service.update(adjustmentId, request, userEmail)
            ResponseEntity.ok(ApiResponse.success(saved.toResponse(), "Movement cost adjustment updated"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.message ?: "Not found"))
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.message ?: "Conflict"))
        } catch (e: Exception) {
            logger.error("Error updating movement cost adjustment", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    @DeleteMapping("/{adjustmentId}")
    fun delete(
        @PathVariable adjustmentId: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<Unit>> {
        val userEmail = httpRequest.getHeader("X-User-Email") ?: "unknown"
        return try {
            service.delete(adjustmentId, userEmail)
            ResponseEntity.ok(ApiResponse.success(Unit, "Movement cost adjustment deleted"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.message ?: "Not found"))
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.message ?: "Conflict"))
        } catch (e: Exception) {
            logger.error("Error deleting movement cost adjustment", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    private fun MovementCostAdjustment.toResponse() = MovementCostAdjustmentResponse(
        adjustmentId = adjustmentId,
        customerId = customerId,
        attachedTo = AdjustmentAttachedRefResponse(attachedTo.type, attachedTo.id, attachedTo.number),
        direction = direction,
        reason = reason,
        ratePerUnitDelta = ratePerUnitDelta,
        notes = notes,
        createdBy = createdBy,
        createdAt = createdAt,
        updatedAt = updatedAt,
        updatedBy = updatedBy,
        billingInvoiceId = billingInvoiceId,
        isLocked = billingInvoiceId != null
    )
}
