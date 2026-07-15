package com.eventledger.account.error;

public class AccountRaceDidNotConvergeException extends RuntimeException {

    public AccountRaceDidNotConvergeException() {
        super("Transaction application did not converge; retrying the same eventId is safe");
    }

    public AccountRaceDidNotConvergeException(Throwable cause) {
        super("Transaction application did not converge; retrying the same eventId is safe", cause);
    }
}
