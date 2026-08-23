package de.pamir.claude.ui.web;

import de.pamir.claude.ui.git.GitException;
import de.pamir.claude.ui.session.DirtyWorktreeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(NoSuchElementException.class)
	ProblemDetail notFound(NoSuchElementException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail badRequest(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler({IllegalStateException.class, GitException.class})
	ProblemDetail conflict(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler(DirtyWorktreeException.class)
	ProblemDetail dirty(DirtyWorktreeException e) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"worktree has uncommitted changes; retry with dirty=commit|stash|discard");
		detail.setProperty("dirtyFiles", e.dirtyFiles());
		return detail;
	}
}
