package com.dentalclinic.feedback.api;

import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.dentalclinic.feedback.application.FeedbackService;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;

@Validated
@RestController
@RequestMapping("/api/feedback")
public class FeedbackReviewsController {
    private final FeedbackService feedback;
    public FeedbackReviewsController(FeedbackService feedback) { this.feedback = feedback; }

    @GetMapping
    public Reviews list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        var result = feedback.reviews(CurrentUser.require(), page, size);
        return new Reviews(result.reviews().getContent(), page, size, result.reviews().getTotalElements(),
                result.reviews().getTotalPages(), result.count(), result.average());
    }

    @GetMapping("/{id:[0-9]+}")
    public FeedbackService.Review detail(@PathVariable Long id) { return feedback.review(CurrentUser.require(), id); }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Reviews(java.util.List<FeedbackService.Review> content, int page, int size,
            long totalElements, int totalPages, long count, Double average) { }
}
