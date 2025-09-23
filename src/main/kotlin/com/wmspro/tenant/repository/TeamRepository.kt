package com.wmspro.tenant.repository

import com.wmspro.tenant.model.Team
import com.wmspro.tenant.model.TeamStatus
import com.wmspro.tenant.model.TeamType
import com.wmspro.tenant.model.TaskSpecialization
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * Repository for Team model
 * This repository operates on tenant-specific databases
 */
@Repository
interface TeamRepository : MongoRepository<Team, String>, CustomTeamRepository {

    /**
     * Finds a team by team code
     */
    fun findByTeamCode(teamCode: String): Optional<Team>

    /**
     * Finds teams by client ID
     */
    fun findByClientId(clientId: Int): List<Team>

    /**
     * Finds teams by client ID and status
     */
    fun findByClientIdAndStatus(clientId: Int, status: TeamStatus): List<Team>

    /**
     * Finds teams by team type
     */
    fun findByTeamType(teamType: TeamType): List<Team>

    /**
     * Finds teams by team lead email
     */
    fun findByTeamLead(teamLead: String): List<Team>

    /**
     * Finds teams by status
     */
    fun findByStatus(status: TeamStatus): List<Team>

    /**
     * Finds teams that have a specific specialization
     */
    @Query("{'specializations': ?0}")
    fun findBySpecialization(specialization: TaskSpecialization): List<Team>

    /**
     * Finds teams assigned to a specific warehouse
     */
    @Query("{'assignedWarehouses': ?0}")
    fun findByAssignedWarehouse(warehouseId: String): List<Team>

    /**
     * Finds teams that work in a specific warehouse zone
     */
    @Query("{'warehouseZones': ?0}")
    fun findByWarehouseZone(zone: String): List<Team>

    /**
     * Finds teams by member email
     */
    @Query("{'members.email': ?0}")
    fun findByMemberEmail(email: String): List<Team>

    /**
     * Checks if a team exists by team code
     */
    fun existsByTeamCode(teamCode: String): Boolean

    /**
     * Checks if a team exists by team name and client ID
     */
    fun existsByTeamNameAndClientId(teamName: String, clientId: Int): Boolean

    /**
     * Deletes a team by team code
     */
    fun deleteByTeamCode(teamCode: String): Long

    /**
     * Counts teams by client ID
     */
    fun countByClientId(clientId: Int): Long

    /**
     * Counts active teams by client ID
     */
    fun countByClientIdAndStatus(clientId: Int, status: TeamStatus): Long

    /**
     * Finds the next available team code
     */
    @Query(value = "{}", fields = "{'teamCode': 1}", sort = "{'teamCode': -1}")
    fun findTopByOrderByTeamCodeDesc(): Optional<Team>
}

/**
 * Custom repository interface for complex Team operations
 */
interface CustomTeamRepository {
    /**
     * Generates the next team code
     */
    fun generateNextTeamCode(): String

    /**
     * Finds teams with available capacity (members < maxMembers)
     */
    fun findTeamsWithAvailableCapacity(clientId: Int): List<Team>

    /**
     * Updates team performance metrics
     */
    fun updatePerformanceMetrics(teamCode: String, metrics: Map<String, Any>): Boolean

    /**
     * Adds a member to a team
     */
    fun addMemberToTeam(teamCode: String, memberEmail: String): Boolean

    /**
     * Removes a member from a team
     */
    fun removeMemberFromTeam(teamCode: String, memberEmail: String): Boolean
}