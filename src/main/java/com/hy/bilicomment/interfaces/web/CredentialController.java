package com.hy.bilicomment.interfaces.web;

import com.hy.bilicomment.application.credential.CredentialService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/credentials")
public class CredentialController {

    private final CredentialService credentialService;

    public CredentialController(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @GetMapping
    public List<CredentialProfile> credentials() {
        return credentialService.list().stream().map(this::profile).toList();
    }

    @PutMapping("/{credentialId}/secret")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replaceSecret(
            @PathVariable String credentialId,
            @Valid @RequestBody ReplaceSecretRequest request) {
        credentialService.replaceSecret(
                TaskController.parseId(credentialId, "credentialId"),
                request.secret());
    }

    @PostMapping("/{credentialId}/validate")
    public CredentialProfile validate(@PathVariable String credentialId) {
        return profile(credentialService.validate(
                TaskController.parseId(credentialId, "credentialId")));
    }

    private CredentialProfile profile(CredentialService.CredentialSummary summary) {
        return new CredentialProfile(
                summary.id(),
                summary.name(),
                summary.enabled(),
                "VALID".equals(summary.validationStatus()),
                summary.lastValidatedAt());
    }

    public record ReplaceSecretRequest(@NotBlank @Size(max = 16384) String secret) {}

    public record CredentialProfile(
            String id,
            String name,
            boolean enabled,
            boolean valid,
            Instant lastValidatedAt) {}
}
