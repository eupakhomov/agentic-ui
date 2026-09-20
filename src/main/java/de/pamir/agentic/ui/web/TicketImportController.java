package de.pamir.agentic.ui.web;

import de.pamir.agentic.ui.concurrent.FireAndForget;
import de.pamir.agentic.ui.integration.TicketImportService;
import de.pamir.agentic.ui.session.SystemSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ticket-to-session prefill: generate a branch name + kickoff prompt from a ticket. */
@RestController
@RequestMapping("/api/tickets")
public class TicketImportController {

	private static final Logger log = LoggerFactory.getLogger(TicketImportController.class);

	public record ImportRequest(String ticketRef) {
	}

	public record EnabledResponse(boolean enabled, boolean warm) {
	}

	private final TicketImportService service;
	private final SystemSessionService systemSessionService;

	public TicketImportController(TicketImportService service, SystemSessionService systemSessionService) {
		this.service = service;
		this.systemSessionService = systemSessionService;
	}

	/**
	 * Also pre-warms the system session and prefetches the ticket list in the background when
	 * Linear is enabled — the dialogs call this on open, so by the time the user clicks Browse
	 * the list is usually already cached (Step A2). Both are fire-and-forget: a failure here is
	 * silently retried by whatever the user does next (Browse, or the dialog simply showing the
	 * cold-start copy).
	 */
	@GetMapping("/import/enabled")
	public EnabledResponse enabled() {
		boolean enabled = service.enabled();
		if (enabled) {
			systemSessionService.warmUp();
			FireAndForget.run("ticket-list-prefetch", log, "ticket list prefetch failed",
					() -> service.listMyTickets(false));
		}
		return new EnabledResponse(enabled, systemSessionService.isWarm());
	}

	@PostMapping("/import")
	public TicketImportService.TicketImportResult importTicket(@RequestBody ImportRequest request) {
		return service.importTicket(request.ticketRef());
	}

	@PostMapping("/recent")
	public TicketImportService.TicketList recentTickets(@RequestParam(defaultValue = "false") boolean refresh) {
		return service.listMyTickets(refresh);
	}
}
