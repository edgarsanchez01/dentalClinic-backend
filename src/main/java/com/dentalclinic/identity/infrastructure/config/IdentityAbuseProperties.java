package com.dentalclinic.identity.infrastructure.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import com.dentalclinic.identity.infrastructure.security.IdentityClientAddress;

/** D4: límites técnicos configurables, sin bloquear login ni consumo de enlaces. */
@ConfigurationProperties(prefix = "dentalclinic.identity.abuse")
public record IdentityAbuseProperties(Duration resetCooldown, Duration resetWindow, int resetMaxPerAccount,
        Duration staffCooldown, Duration staffWindow, int staffMaxPerAccount,
        Duration ipWindow, int resetMaxPerIp, int staffMaxPerIp, int maxIpEntries,
        List<String> trustedProxies) {

    public IdentityAbuseProperties {
        resetCooldown = resetCooldown == null ? Duration.ofMinutes(1) : resetCooldown;
        resetWindow = resetWindow == null ? Duration.ofHours(1) : resetWindow;
        staffCooldown = staffCooldown == null ? Duration.ofMinutes(1) : staffCooldown;
        staffWindow = staffWindow == null ? Duration.ofHours(1) : staffWindow;
        ipWindow = ipWindow == null ? Duration.ofMinutes(15) : ipWindow;
        resetMaxPerAccount = resetMaxPerAccount == 0 ? 3 : resetMaxPerAccount;
        staffMaxPerAccount = staffMaxPerAccount == 0 ? 5 : staffMaxPerAccount;
        resetMaxPerIp = resetMaxPerIp == 0 ? 20 : resetMaxPerIp;
        staffMaxPerIp = staffMaxPerIp == 0 ? 30 : staffMaxPerIp;
        maxIpEntries = maxIpEntries == 0 ? 10000 : maxIpEntries;
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        if (resetCooldown.isNegative() || resetCooldown.isZero() || staffCooldown.isNegative()
                || staffCooldown.isZero() || resetWindow.isNegative() || resetWindow.isZero()
                || staffWindow.isNegative() || staffWindow.isZero() || ipWindow.isNegative()
                || ipWindow.isZero() || resetMaxPerAccount < 1 || staffMaxPerAccount < 1
                || resetMaxPerIp < 1 || staffMaxPerIp < 1 || maxIpEntries < 1) {
            throw new IllegalArgumentException("Los límites de identidad deben ser positivos.");
        }
        for (String proxy : trustedProxies) {
            // Solo direcciones numéricas/CIDR; no se resuelven hostnames de configuración ni cabeceras.
            if (!proxy.matches("[0-9a-fA-F:.]+(/[0-9]{1,3})?")
                    || IdentityClientAddress.numeric(proxy.split("/", 2)[0]) == null) {
                throw new IllegalArgumentException("Proxy confiable de identidad inválido.");
            }
            new IpAddressMatcher(proxy);
        }
    }
}
