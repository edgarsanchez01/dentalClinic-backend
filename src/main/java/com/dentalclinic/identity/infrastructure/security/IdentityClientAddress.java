package com.dentalclinic.identity.infrastructure.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import com.dentalclinic.identity.infrastructure.config.IdentityAbuseProperties;

import jakarta.servlet.http.HttpServletRequest;

/** D4: peer TCP por defecto; XFF solo tras proxies numéricos/CIDR explícitamente confiables. */
@Component
public class IdentityClientAddress {
    private final List<IpAddressMatcher> trusted;

    public IdentityClientAddress(IdentityAbuseProperties properties) {
        trusted = properties.trustedProxies().stream().map(IpAddressMatcher::new).toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = numeric(request.getRemoteAddr());
        if (peer == null) {
            return "unknown-peer";
        }
        String header = request.getHeader("X-Forwarded-For");
        if (!isTrusted(peer) || header == null || header.length() > 1024) {
            return peer;
        }
        var headers = request.getHeaders("X-Forwarded-For");
        if (headers != null && headers.hasMoreElements()) {
            headers.nextElement();
            if (headers.hasMoreElements()) {
                return peer; // No interpretar ambiguamente múltiples campos de cabecera.
            }
        }
        String[] hops = header.split(",", -1);
        if (hops.length > 16) {
            return peer;
        }
        String current = peer;
        for (int i = hops.length - 1; i >= 0; i--) {
            if (!isTrusted(current)) {
                return current;
            }
            String next = numeric(hops[i].strip());
            if (next == null) {
                return peer; // Cadena malformada: no premiar la cabecera manipulada.
            }
            current = next;
        }
        return current;
    }

    private boolean isTrusted(String address) {
        return trusted.stream().anyMatch(matcher -> matcher.matches(address));
    }

    public static String numeric(String value) {
        if (value == null || !value.matches("[0-9a-fA-F:.]+")
                || (!value.contains(":") && !value.contains("."))) {
            return null;
        }
        if (!value.contains(":")) {
            if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                return null;
            }
            for (String octet : value.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
        }
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }
}
