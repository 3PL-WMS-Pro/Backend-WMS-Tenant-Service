package com.wmspro.tenant.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime
import java.time.LocalDate

/**
 * Team Model - Manages operational teams and their configurations
 * Collection: teams
 * Database: Tenant-specific database
 */
@Document(collection = "teams")
@CompoundIndex(def = "{'clientId': 1, 'status': 1}")
data class Team(
    @Id
    val teamCode: String, // Primary key: "TEAM-001", "TEAM-002", etc.

    val clientId: Int,

    val teamName: String,

    val teamType: TeamType = TeamType.OPERATIONAL,

    val description: String? = null,

    val shiftInfo: ShiftInfo,

    val teamLead: String, // Email in lowercase

    val members: List<TeamMember> = emptyList(),

    val maxMembers: Int = 50,

    val specializations: List<TaskSpecialization> = emptyList(),

    val warehouseZones: List<String> = emptyList(),

    val assignedWarehouses: List<String> = emptyList(),

    val performanceMetrics: PerformanceMetrics? = null,

    val status: TeamStatus = TeamStatus.ACTIVE,

    val createdBy: String? = null,

    @CreatedDate
    val createdAt: LocalDateTime? = null,

    @LastModifiedDate
    val updatedAt: LocalDateTime? = null
) {
    init {
        require(teamCode.matches(Regex("^TEAM-\\d{3}$"))) { "Team code must match pattern TEAM-XXX" }
        require(teamLead == teamLead.lowercase()) { "Team lead email must be lowercase" }
        require(members.size <= maxMembers) { "Number of members cannot exceed max members limit" }
    }

    companion object {
        fun generateTeamCode(sequenceNumber: Int): String {
            return "TEAM-${sequenceNumber.toString().padStart(3, '0')}"
        }
    }

    fun addMember(email: String): Team {
        require(members.size < maxMembers) { "Team has reached maximum member capacity" }
        val newMember = TeamMember(
            email = email.lowercase(),
            joinedDate = LocalDate.now(),
            isActive = true
        )
        return this.copy(members = members + newMember)
    }

    fun removeMember(email: String): Team {
        return this.copy(members = members.filter { it.email != email.lowercase() })
    }

    fun deactivateMember(email: String): Team {
        return this.copy(
            members = members.map {
                if (it.email == email.lowercase()) {
                    it.copy(isActive = false)
                } else {
                    it
                }
            }
        )
    }

    fun updatePerformanceMetrics(metrics: PerformanceMetrics): Team {
        return this.copy(
            performanceMetrics = metrics.copy(lastCalculated = LocalDateTime.now())
        )
    }
}

/**
 * Team member information
 */
data class TeamMember(
    val email: String,
    val joinedDate: LocalDate,
    val isActive: Boolean = true
) {
    init {
        require(email == email.lowercase()) { "Email must be lowercase" }
    }
}

/**
 * Shift configuration
 */
data class ShiftInfo(
    val shiftType: ShiftType,
    val shiftTiming: ShiftTiming,
    val startTime: String? = null, // Format: "HH:MM"
    val endTime: String? = null, // Format: "HH:MM"
    val workingDays: List<DayOfWeek> = emptyList(),
    val timezone: String = "UTC"
) {
    init {
        if (startTime != null) {
            require(startTime.matches(Regex("^([01]\\d|2[0-3]):[0-5]\\d$"))) {
                "Start time must be in HH:MM format"
            }
        }
        if (endTime != null) {
            require(endTime.matches(Regex("^([01]\\d|2[0-3]):[0-5]\\d$"))) {
                "End time must be in HH:MM format"
            }
        }
    }
}

/**
 * Performance metrics for the team
 */
data class PerformanceMetrics(
    val fulfillmentsPerHourTarget: Int? = null,
    val accuracyTargetPercentage: Double? = null,
    val currentMonthStats: CurrentMonthStats? = null,
    val lastCalculated: LocalDateTime? = null
) {
    init {
        if (accuracyTargetPercentage != null) {
            require(accuracyTargetPercentage in 0.0..100.0) {
                "Accuracy percentage must be between 0 and 100"
            }
        }
    }
}

/**
 * Current month performance statistics
 */
data class CurrentMonthStats(
    val fulfillmentsProcessed: Int = 0,
    val accuracyPercentage: Double = 0.0,
    val averageFulfillmentTimeMinutes: Double = 0.0
) {
    init {
        require(accuracyPercentage in 0.0..100.0) {
            "Accuracy percentage must be between 0 and 100"
        }
        require(averageFulfillmentTimeMinutes >= 0) {
            "Average fulfillment time cannot be negative"
        }
    }
}

/**
 * Team types
 */
enum class TeamType {
    OPERATIONAL,
    MANAGEMENT,
    SUPPORT
}

/**
 * Shift types
 */
enum class ShiftType {
    FIXED,
    ROTATING,
    FLEXIBLE,
    ON_CALL
}

/**
 * Shift timings
 */
enum class ShiftTiming {
    MORNING,
    AFTERNOON,
    NIGHT,
    FULL_DAY
}

/**
 * Days of the week
 */
enum class DayOfWeek {
    MON, TUE, WED, THU, FRI, SAT, SUN
}

/**
 * Task specializations aligned with 9 task types
 */
enum class TaskSpecialization {
    OFFLOADING,
    RECEIVING,
    PUT_AWAY,
    PICKING,
    PACK_MOVE,
    PICK_PACK_MOVE,
    LOADING,
    COUNTING,
    TRANSFER
}

/**
 * Team status
 */
enum class TeamStatus {
    ACTIVE,
    INACTIVE,
    DISBANDED,
    TRAINING
}