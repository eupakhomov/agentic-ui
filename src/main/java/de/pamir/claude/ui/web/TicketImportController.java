package de.pamir.claude.ui.web;

import de.pamir.claude.ui.integration.TicketImportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Ticket-to-session prefill: generate a branch name + kickoff prompt from a ticket. */
@RestController
@RequestMapping("/api/tickets")
public class TicketImportController {

	public record ImportRequest(String ticketRef) {
	}

	public record EnabledResponse(boolean enabled) {
	}

	private final TicketImportService service;

	public TicketImportController(TicketImportService service) {
		this.service = service;
	}

	@GetMapping("/import/enabled")
	public EnabledResponse enabled() {
		return new EnabledResponse(service.enabled());
	}

	@PostMapping("/import")
	public TicketImportService.TicketImportResult importTicket(@RequestBody ImportRequest request) {
		return service.importTicket(request.ticketRef());
	}

	@PostMapping("/recent")
	public List<TicketImportService.TicketSummary> recentTickets() {
		return service.listMyTickets();
	}
}
