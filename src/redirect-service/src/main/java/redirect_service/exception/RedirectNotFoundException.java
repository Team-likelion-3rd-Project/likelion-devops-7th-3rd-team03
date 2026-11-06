package redirect_service.exception;

public class RedirectNotFoundException extends RuntimeException {

    public enum Reason {
        NOT_FOUND,
        EXPIRED,
        DISABLED
    }

    private final Reason reason;

    public RedirectNotFoundException() {
        this(Reason.NOT_FOUND);
    }

    public RedirectNotFoundException(Reason reason) {
        super("Redirect link was not found");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
