package com.appsteam.launchpad.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

/**
 * Friendly error pages for the cases the app raises itself. Everything else (e.g. Spring Security's 403 for
 * /admin, container 404s) goes through Spring Boot's /error handling, which renders templates/error/*.html.
 */
@ControllerAdvice
public class ErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandling.class);

    @ExceptionHandler(NotFoundException.class)
    public ModelAndView notFound(NotFoundException e, HttpServletRequest request) {
        return page(HttpStatus.NOT_FOUND, "error/404", e.getMessage(), request, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ModelAndView noResource(NoResourceFoundException e, HttpServletRequest request) {
        return page(HttpStatus.NOT_FOUND, "error/404", "There is nothing at this address.", request, null);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ModelAndView forbidden(ForbiddenException e, HttpServletRequest request) {
        ModelAndView mav = page(HttpStatus.FORBIDDEN, "error/403", e.getMessage(), request, null);
        mav.addObject("requiredGroups", e.getRequiredGroups());
        return mav;
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView unexpected(Exception e, HttpServletRequest request) {
        if (e instanceof ErrorResponse er) {   // Spring MVC's own 4xx (405, 400, 415 …): keep their status
            HttpStatus status = HttpStatus.resolve(er.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                String view = status == HttpStatus.NOT_FOUND ? "error/404" : status == HttpStatus.FORBIDDEN ? "error/403" : "error";
                return page(status, view, status.getReasonPhrase(), request, null);
            }
        }
        String ref = UUID.randomUUID().toString().substring(0, 8);
        log.error("unhandled error [{}] on {} {}", ref, request.getMethod(), request.getRequestURI(), e);
        return page(HttpStatus.INTERNAL_SERVER_ERROR, "error/500", "Something went wrong on our side.", request, ref);
    }

    private static ModelAndView page(HttpStatus status, String view, String message, HttpServletRequest request, String ref) {
        ModelAndView mav = new ModelAndView(view);
        mav.setStatus(status);
        mav.addObject("status", status.value());
        mav.addObject("error", status.getReasonPhrase());
        mav.addObject("message", message);
        mav.addObject("path", request.getRequestURI());
        mav.addObject("timestamp", Clock.now());
        mav.addObject("reference", ref);
        return mav;
    }
}
