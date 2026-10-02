package rw.bank.ibanking.onboarding.web;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.onboarding.domain.NotificationEntity;
import rw.bank.ibanking.onboarding.service.Notifications;

/**
 * THE CUSTOMER'S OWN NOTIFICATIONS.
 *
 * <p>WHAT THIS REPLACED: nothing. The portal had a dashboard panel, a full page, a
 * mark-as-read action and a mark-all-read action, all pointed at endpoints that did not
 * exist — MSW answered them from an array that starts empty on every page load. The panel
 * therefore read "Nothing new." permanently, which is a claim rather than a blank: a
 * customer reads an empty notification list as a record that nothing happened.
 *
 * <p>SCOPED TO THE CALLER, taken from the session. No customer id appears in any path or
 * body here, because an endpoint that accepted one would be a way to read — or silently
 * mark read — somebody else's security notifications by editing a URL. That matters more
 * here than almost anywhere: the value of the password-changed row is that it is unread and
 * at the top when its owner next signs in.
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationsController {

    private final Notifications notifications;

    NotificationsController(Notifications notifications) {
        this.notifications = notifications;
    }

    /**
     * One notification, as the portal renders it.
     *
     * <p>{@code severity} IS DERIVED FROM THE KIND and not stored — see
     * {@code NotificationSeverity}. It travels because the client sorts and styles on it.
     *
     * <p>{@code link} IS ALWAYS AN IN-APP PATH, enforced by the entity and again by V20's
     * constraint. A notification carries text the bank did not author — a staff member's
     * reason, a payee name the customer typed — so an absolute URL rendered out of one would
     * be phishing with the bank's own domain behind it.
     */
    record NotificationView(
            String id,
            String title,
            String body,
            String createdAt,
            boolean read,
            String severity,
            String link) {

        static NotificationView of(NotificationEntity row) {
            return new NotificationView(
                    row.id().toString(),
                    row.title(),
                    row.body(),
                    row.createdAt().toString(),
                    row.isRead(),
                    row.kind().severity().name(),
                    row.link());
        }
    }

    @GetMapping
    List<NotificationView> list() {
        return notifications.forCustomer(AuthController.currentCustomerId()).stream()
                .map(NotificationView::of)
                .toList();
    }

    /**
     * Marks one read, and returns it.
     *
     * <p>RETURNS THE ROW rather than 204, so the client can render the new state from the
     * server's answer instead of assuming it. The alternative is a screen that shows a
     * notification as read because the request did not throw.
     */
    @PostMapping("/{id}/read")
    NotificationView read(@PathVariable UUID id) {
        return NotificationView.of(
                notifications.markRead(AuthController.currentCustomerId(), id));
    }

    /**
     * Marks everything read, and returns the list as it now stands.
     *
     * <p>THE WHOLE LIST, not a count, because that is what the screen draws next and a
     * second round trip to fetch it would leave a window in which the page disagrees with
     * the server about what the customer has seen.
     */
    @PostMapping("/read-all")
    List<NotificationView> readAll() {
        UUID customerId = AuthController.currentCustomerId();
        notifications.markAllRead(customerId);

        return notifications.forCustomer(customerId).stream().map(NotificationView::of).toList();
    }
}
