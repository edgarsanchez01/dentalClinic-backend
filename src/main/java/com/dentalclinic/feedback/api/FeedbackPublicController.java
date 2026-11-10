package com.dentalclinic.feedback.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.dentalclinic.feedback.application.FeedbackService;

@RestController
@RequestMapping("/api/feedback/invitations")
public class FeedbackPublicController {
    private final FeedbackService feedback;
    public FeedbackPublicController(FeedbackService feedback) { this.feedback = feedback; }

    @GetMapping("/{token}")
    public ResponseEntity<LinkState> validate(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer").body(new LinkState(feedback.state(token)));
    }

    @PostMapping("/{token}")
    public ResponseEntity<LinkState> respond(@PathVariable String token, @Valid @RequestBody Answer answer) {
        String state = feedback.respond(token, answer.rating(), answer.comment());
        int status = "RESPONDED".equals(state) ? 200 : "USED".equals(state) ? 409 : 410;
        return ResponseEntity.status(status).cacheControl(org.springframework.http.CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer").body(new LinkState(state));
    }

    public record LinkState(String state) { }
    public record Answer(@NotNull @Min(1) @Max(5) @JsonDeserialize(using = RatingDeserializer.class) Integer rating,
            @Size(max = 2000) String comment) { }
}
