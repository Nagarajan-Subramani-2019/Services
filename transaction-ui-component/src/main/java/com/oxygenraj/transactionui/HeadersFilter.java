package com.oxygenraj.transactionui;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class HeadersFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        res.setHeader("Cache-Control","no-store"); res.setHeader("X-Content-Type-Options","nosniff");
        res.setHeader("Referrer-Policy","no-referrer"); res.setHeader("X-Frame-Options","DENY");
        res.setHeader("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'");
        chain.doFilter(req,res);
    }
}
