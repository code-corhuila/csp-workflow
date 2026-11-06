package co.edu.corhuila.csp.workflow.adapter.in.http;

import java.util.List;

/** The claims of a token whose signature and expiration were verified. */
public record VerifiedToken(String subject, List<String> audiences) { }
