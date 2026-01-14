package org.example.support.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.support.service.SupportService

/**
 * Модуль Ktor для REST API поддержки
 */
fun Application.supportApiModule(supportService: SupportService) {
    routing {
        route("/api/v1/support") {

            // Health check
            get("/health") {
                val ragStatus = supportService.checkRagStatus()
                val crmStatus = supportService.checkCrmStatus()

                call.respond(
                    HealthResponse(
                        status = "ok",
                        version = "1.0.0",
                        ragAvailable = ragStatus.available,
                        crmAvailable = crmStatus.available,
                        indexedFiles = ragStatus.indexedFiles
                    )
                )
            }

            // Основной endpoint чата
            post("/chat") {
                try {
                    val request = call.receive<SupportChatRequest>()

                    if (request.message.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Message cannot be empty", 400)
                        )
                        return@post
                    }

                    val response = supportService.processMessage(request)
                    call.respond(response)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Error processing message: ${e.message}", 500)
                    )
                }
            }

            // Получить тикеты пользователя
            get("/tickets/{user_id}") {
                val userId = call.parameters["user_id"]
                if (userId.isNullOrBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("user_id is required", 400)
                    )
                    return@get
                }

                try {
                    val tickets = supportService.getUserTickets(userId)
                    call.respond(mapOf("tickets" to tickets))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Error fetching tickets: ${e.message}", 500)
                    )
                }
            }

            // Получить конкретный тикет
            get("/ticket/{ticket_id}") {
                val ticketId = call.parameters["ticket_id"]
                if (ticketId.isNullOrBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("ticket_id is required", 400)
                    )
                    return@get
                }

                try {
                    val ticket = supportService.getTicket(ticketId)
                    if (ticket != null) {
                        call.respond(ticket)
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("Ticket not found", 404)
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Error fetching ticket: ${e.message}", 500)
                    )
                }
            }

            // Создать новый тикет
            post("/ticket") {
                try {
                    val request = call.receive<CreateTicketRequest>()

                    if (request.userId.isBlank() || request.subject.isBlank() || request.message.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("user_id, subject and message are required", 400)
                        )
                        return@post
                    }

                    val ticket = supportService.createTicket(request)
                    if (ticket != null) {
                        call.respond(HttpStatusCode.Created, ticket)
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Failed to create ticket", 500)
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Error creating ticket: ${e.message}", 500)
                    )
                }
            }

            // Получить информацию о пользователе
            get("/user/{user_id}") {
                val userId = call.parameters["user_id"]
                if (userId.isNullOrBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("user_id is required", 400)
                    )
                    return@get
                }

                try {
                    val user = supportService.getUser(userId)
                    if (user != null) {
                        call.respond(user)
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("User not found", 404)
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Error fetching user: ${e.message}", 500)
                    )
                }
            }
        }
    }
}
