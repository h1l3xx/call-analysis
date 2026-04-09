package com.malikov.routing

import com.malikov.auth.Role
import com.malikov.dto.CreatePromptTemplateRequest
import com.malikov.dto.SuggestRequest
import com.malikov.dto.SuggestResponse
import com.malikov.dto.UpdatePromptTemplateRequest
import com.malikov.service.InternalCallEvaluator
import com.malikov.service.PromptTemplateService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.promptTemplateRoutes(service: PromptTemplateService, evaluator: InternalCallEvaluator) {
    route("/prompt-templates") {

        get {
            val p = requireTenantRole(Role.CLIENT_ADMIN, Role.TEAM_LEAD)
            call.respond(service.list(p.schema!!))
        }

        get("/{id}") {
            val p = requireTenantRole(Role.CLIENT_ADMIN, Role.TEAM_LEAD)
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing id")
            call.respond(service.getById(p.schema!!, id))
        }

        post {
            val p = requireTenantRole(Role.CLIENT_ADMIN)
            val request = call.receive<CreatePromptTemplateRequest>()
            call.respond(HttpStatusCode.Created, service.create(p.schema!!, request))
        }

        put("/{id}") {
            val p = requireTenantRole(Role.CLIENT_ADMIN)
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing id")
            val request = call.receive<UpdatePromptTemplateRequest>()
            call.respond(service.update(p.schema!!, id, request.content))
        }

        post("/{id}/reset") {
            val p = requireTenantRole(Role.CLIENT_ADMIN)
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing id")
            call.respond(service.reset(p.schema!!, id))
        }

        post("/{id}/suggest") {
            requireTenantRole(Role.CLIENT_ADMIN)
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing id")
            val request = call.receive<SuggestRequest>()
            require(request.description.isNotBlank()) { "Описание не может быть пустым" }
            val suggestions = evaluator.generateSuggestions(id, request.description)
            call.respond(SuggestResponse(suggestions))
        }

        delete("/{id}") {
            val p = requireTenantRole(Role.CLIENT_ADMIN)
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing id")
            val deleted = service.delete(p.schema!!, id)
            if (deleted) call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            else call.respond(HttpStatusCode.NotFound, mapOf("error" to "Template not found"))
        }
    }
}
