package com.wmspro.tenant.service

import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.config.MultiTenantMongoTemplate
import com.wmspro.tenant.model.*
import com.wmspro.tenant.repository.TeamRepository
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Service for managing teams
 * This service operates on tenant-specific databases
 */
@Service
class TeamService(
    private val teamRepository: TeamRepository,
    private val multiTenantMongoTemplate: MultiTenantMongoTemplate
) {
    private val logger = LoggerFactory.getLogger(TeamService::class.java)

    /**
     * Gets the current client ID from tenant context
     */
    private fun requireCurrentClientId(): Int {
        val tenantId = TenantContext.getCurrentTenant()
            ?: throw IllegalStateException("No tenant context has been set for this request")
        return tenantId.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid tenant ID format: $tenantId")
    }

    /**
     * Creates a new team
     */
    @Transactional
    fun createTeam(team: Team): Team {
        val clientId = requireCurrentClientId()
        logger.info("Creating new team: ${team.teamName} for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant { mongoTemplate ->
            // Validate team doesn't already exist
            if (teamRepository.existsByTeamNameAndClientId(team.teamName, clientId)) {
                throw IllegalArgumentException("Team with name ${team.teamName} already exists for this client")
            }

            // Ensure client ID matches context
            val teamWithClientId = team.copy(clientId = clientId)

            val savedTeam = teamRepository.save(teamWithClientId)
            logger.info("Successfully created team: ${savedTeam.teamCode} for client ID: $clientId")
            savedTeam
        }
    }

    /**
     * Gets a team by team code
     */
    fun getTeamByCode(teamCode: String): Team? {
        val clientId = requireCurrentClientId()
        logger.debug("Fetching team: $teamCode for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant {
            teamRepository.findByTeamCode(teamCode).orElse(null)
        }
    }

    /**
     * Updates a team
     */
    @Transactional
    fun updateTeam(teamCode: String, updates: Map<String, Any>): Team {
        val clientId = requireCurrentClientId()
        logger.info("Updating team: $teamCode for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant {
            val existingTeam = teamRepository.findByTeamCode(teamCode).orElse(null)
                ?: throw IllegalArgumentException("Team with code $teamCode not found")

            // Ensure team belongs to current tenant
            if (existingTeam.clientId != clientId) {
                throw IllegalArgumentException("Team does not belong to current tenant")
            }

            var updatedTeam = existingTeam

            // Apply updates
            updates["teamName"]?.let { teamName ->
                updatedTeam = updatedTeam.copy(teamName = teamName as String)
            }

            updates["teamType"]?.let { type ->
                updatedTeam = updatedTeam.copy(teamType = TeamType.valueOf(type as String))
            }

            updates["description"]?.let { description ->
                updatedTeam = updatedTeam.copy(description = description as String)
            }

            updates["teamLead"]?.let { lead ->
                updatedTeam = updatedTeam.copy(teamLead = (lead as String).lowercase())
            }

            updates["maxMembers"]?.let { max ->
                updatedTeam = updatedTeam.copy(maxMembers = (max as Number).toInt())
            }

            updates["status"]?.let { status ->
                updatedTeam = updatedTeam.copy(status = TeamStatus.valueOf(status as String))
            }

            updates["specializations"]?.let { specs ->
                if (specs is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val specializations = (specs as List<String>).map { TaskSpecialization.valueOf(it) }
                    updatedTeam = updatedTeam.copy(specializations = specializations)
                }
            }

            updates["warehouseZones"]?.let { zones ->
                if (zones is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    updatedTeam = updatedTeam.copy(warehouseZones = zones as List<String>)
                }
            }

            updates["assignedWarehouses"]?.let { warehouses ->
                if (warehouses is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    updatedTeam = updatedTeam.copy(assignedWarehouses = warehouses as List<String>)
                }
            }

            val savedTeam = teamRepository.save(updatedTeam)
            logger.info("Successfully updated team: $teamCode for client ID: $clientId")
            savedTeam
        }
    }

    /**
     * Deletes a team
     */
    @Transactional
    fun deleteTeam(teamCode: String) {
        val clientId = requireCurrentClientId()
        logger.info("Deleting team: $teamCode for client ID: $clientId")

        multiTenantMongoTemplate.withCurrentTenant {
            val team = teamRepository.findByTeamCode(teamCode).orElse(null)
                ?: throw IllegalArgumentException("Team with code $teamCode not found")

            if (team.clientId != clientId) {
                throw IllegalArgumentException("Team does not belong to current tenant")
            }

            teamRepository.deleteByTeamCode(teamCode)
            logger.info("Successfully deleted team: $teamCode for client ID: $clientId")
        }
    }

    /**
     * Gets all teams for current tenant
     */
    fun getAllTeams(): List<Team> {
        val clientId = requireCurrentClientId()
        logger.debug("Fetching all teams for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant {
            teamRepository.findByClientId(clientId)
        }
    }

    /**
     * Gets active teams for current tenant
     */
    fun getActiveTeams(): List<Team> {
        val clientId = requireCurrentClientId()
        logger.debug("Fetching active teams for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant {
            teamRepository.findByClientIdAndStatus(clientId, TeamStatus.ACTIVE)
        }
    }

    /**
     * Adds a member to a team
     */
    @Transactional
    fun addMemberToTeam(teamCode: String, memberEmail: String): Team {
        val clientId = requireCurrentClientId()
        logger.info("Adding member $memberEmail to team $teamCode for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant {
            val team = teamRepository.findByTeamCode(teamCode).orElse(null)
                ?: throw IllegalArgumentException("Team with code $teamCode not found")

            if (team.clientId != clientId) {
                throw IllegalArgumentException("Team does not belong to current tenant")
            }

            val updatedTeam = team.addMember(memberEmail)
            val savedTeam = teamRepository.save(updatedTeam)
            logger.info("Successfully added member to team $teamCode")
            savedTeam
        }
    }

    /**
     * Removes a member from a team
     */
    @Transactional
    fun removeMemberFromTeam(teamCode: String, memberEmail: String): Team {
        val clientId = requireCurrentClientId()
        logger.info("Removing member $memberEmail from team $teamCode for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant {
            val team = teamRepository.findByTeamCode(teamCode).orElse(null)
                ?: throw IllegalArgumentException("Team with code $teamCode not found")

            if (team.clientId != clientId) {
                throw IllegalArgumentException("Team does not belong to current tenant")
            }

            val updatedTeam = team.removeMember(memberEmail)
            val savedTeam = teamRepository.save(updatedTeam)
            logger.info("Successfully removed member from team $teamCode")
            savedTeam
        }
    }

    /**
     * Updates team performance metrics
     */
    @Transactional
    fun updatePerformanceMetrics(teamCode: String, metrics: PerformanceMetrics): Team {
        val clientId = requireCurrentClientId()
        logger.info("Updating performance metrics for team $teamCode")

        return multiTenantMongoTemplate.withCurrentTenant {
            val team = teamRepository.findByTeamCode(teamCode).orElse(null)
                ?: throw IllegalArgumentException("Team with code $teamCode not found")

            if (team.clientId != clientId) {
                throw IllegalArgumentException("Team does not belong to current tenant")
            }

            val updatedTeam = team.updatePerformanceMetrics(metrics)
            val savedTeam = teamRepository.save(updatedTeam)
            logger.info("Successfully updated performance metrics for team $teamCode")
            savedTeam
        }
    }

    /**
     * Finds teams by specialization
     */
    fun findTeamsBySpecialization(specialization: TaskSpecialization): List<Team> {
        val clientId = requireCurrentClientId()
        logger.debug("Finding teams with specialization $specialization for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant { mongoTemplate ->
            val query = Query(
                Criteria.where("clientId").`is`(clientId)
                    .and("specializations").`is`(specialization.name)
            )
            mongoTemplate.find(query, Team::class.java)
        }
    }

    /**
     * Finds teams by warehouse
     */
    fun findTeamsByWarehouse(warehouseId: String): List<Team> {
        val clientId = requireCurrentClientId()
        logger.debug("Finding teams for warehouse $warehouseId")

        return multiTenantMongoTemplate.withCurrentTenant { mongoTemplate ->
            val query = Query(
                Criteria.where("clientId").`is`(clientId)
                    .and("assignedWarehouses").`is`(warehouseId)
            )
            mongoTemplate.find(query, Team::class.java)
        }
    }

    /**
     * Finds teams with available capacity
     */
    fun findTeamsWithAvailableCapacity(): List<Team> {
        val clientId = requireCurrentClientId()
        logger.debug("Finding teams with available capacity for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant { mongoTemplate ->
            val teams = teamRepository.findByClientIdAndStatus(clientId, TeamStatus.ACTIVE)
            teams.filter { team ->
                team.members.size < team.maxMembers
            }
        }
    }

    /**
     * Generates next team code
     */
    fun generateNextTeamCode(): String {
        return multiTenantMongoTemplate.withCurrentTenant { mongoTemplate ->
            val lastTeam = teamRepository.findTopByOrderByTeamCodeDesc().orElse(null)
            if (lastTeam != null) {
                val lastNumber = lastTeam.teamCode.substring(5).toInt()
                Team.generateTeamCode(lastNumber + 1)
            } else {
                Team.generateTeamCode(1)
            }
        }
    }

    /**
     * Gets team member count
     */
    fun getTeamMemberCount(teamCode: String): Int {
        val clientId = requireCurrentClientId()
        logger.debug("Getting member count for team $teamCode")

        return multiTenantMongoTemplate.withCurrentTenant {
            val team = teamRepository.findByTeamCode(teamCode).orElse(null)
                ?: throw IllegalArgumentException("Team with code $teamCode not found")

            if (team.clientId != clientId) {
                throw IllegalArgumentException("Team does not belong to current tenant")
            }

            team.members.count { it.isActive }
        }
    }

    /**
     * Finds teams by member email
     */
    fun findTeamsByMember(email: String): List<Team> {
        val clientId = requireCurrentClientId()
        logger.debug("Finding teams for member $email")

        return multiTenantMongoTemplate.withCurrentTenant { mongoTemplate ->
            val query = Query(
                Criteria.where("clientId").`is`(clientId)
                    .and("members.email").`is`(email.lowercase())
            )
            mongoTemplate.find(query, Team::class.java)
        }
    }
}