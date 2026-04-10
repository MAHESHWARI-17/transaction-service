package com.bank.transactionservice.exception;

public class CustomExceptions {

    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String msg) { super(msg); }
    }

    public static class InvalidPinException extends RuntimeException {
        public InvalidPinException(String msg) { super(msg); }
    }

    public static class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException(String msg) { super(msg); }
    }

    public static class DailyLimitExceededException extends RuntimeException {
        public DailyLimitExceededException(String msg) { super(msg); }
    }

    public static class NewRecipientLimitExceededException extends RuntimeException {
        public NewRecipientLimitExceededException(String msg) { super(msg); }
    }

    public static class AccountNotActiveException extends RuntimeException {
        public AccountNotActiveException(String msg) { super(msg); }
    }

    public static class SameAccountTransferException extends RuntimeException {
        public SameAccountTransferException(String msg) { super(msg); }
    }
}
