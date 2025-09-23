package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.model.Team
import com.wmspro.tenant.service.TeamService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import javax.validation.Valid

/**
 * Controller for team operations
 * Manages teams in tenant-specific databases
 */
@RestController
@RequestMapping("/api/v1/teams")
@Tag(name = "Team Management", description = "APIs for managing teams and shifts")
class TeamController(
    private val teamService: TeamService
) {
    private val logger = LoggerFactory.getLogger(TeamController::class.java)

    @PostMapping
    @Operation(summary = "Create team", description = "Creates a new team in tenant database")
    fun createTeam(
        @Valid @RequestBody team: Team
    ): ResponseEntity<ApiResponse<Team>> {
        logger.info("Creating new team: ${team.teamName}")

        return try {
            val createdTeam = teamService.createTeam(team)
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(
                    data = createdTeam,
                    message = "Team created successfully"
                )
            )
        } catch (e: Exception) {
            logger.error("Error creating team", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error(
                    message = e.message ?: "Failed to create team"
                )
            )
        }
    }

    @GetMapping("/{teamId}")
    @Operation(summary = "Get team by ID", description = "Retrieves a team by its ID")
    fun getTeam(
        @PathVariable teamId: String
    ): ResponseEntity<ApiResponse<Team>> {
        logger.info("Fetching team with ID: $teamId")

        val team = teamService.getTeam(teamId)
        return if (team != null) {
            ResponseEntity.ok(
                ApiResponse.success(
                    data = team,
                    message = "Team retrieved successfully"
                )
            )
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(
                    message = "Team not found with ID: $teamId"
                )
            )
        }
    }

    @GetMapping
    @Operation(summary = "List all teams", description = "Retrieves all teams for the tenant")
    fun listTeams(): ResponseEntity<ApiResponse<List<Team>>> {
        logger.info("Fetching all teams for tenant")

        val teams = teamService.getAllTeams()
        return ResponseEntity.ok(
            ApiResponse.success(
                data = teams,
                message = "Teams retrieved successfully"
            )
        )
    }

    @PutMapping("/{teamId}")
    @Operation(summary = "Update team", description = "Updates an existing team")
    fun updateTeam(
        @PathVariable teamId: String,
        @Valid @RequestBody team: Team
    ): ResponseEntity<ApiResponse<Team>> {
        logger.info("Updating team with ID: $teamId")

        return try {
            val updatedTeam = teamService.updateTeam(teamId, team)
            if (updatedTeam != null) {
                ResponseEntity.ok(
                    ApiResponse.success(
                        data = updatedTeam,
                        message = "Team updated successfully"
                    )
                )
            } else {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error(
                        message = "Team not found with ID: $teamId"
                    )
                )
            }
        } catch (e: Exception) {
            logger.error("Error updating team", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error(
                    message = e.message ?: "Failed to update team"
                )
            )
        }
    }

    @DeleteMapping("/{teamId}")
    @Operation(summary = "Delete team", description = "Deletes a team")
    fun deleteTeam(
        @PathVariable teamId: String
    ): ResponseEntity<ApiResponse<Void>> {
        logger.info("Deleting team with ID: $teamId")

        return try {
            teamService.deleteTeam(teamId)
            ResponseEntity.ok(
                ApiResponse.success(
                    data = null,
                    message = "Team deleted successfully"
                )
            )
        } catch (e: Exception) {
            logger.error("Error deleting team", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error(
                    message = e.message ?: "Failed to delete team"
                )
            )
        }
    }
}