package com.eventledger.account;

import com.eventledger.account.api.ApplyTransactionRequest;
import com.eventledger.account.domain.TransactionData;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.error.AccountRequestValidationException;
import com.eventledger.account.service.AccountTransactionRequestValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTransactionRequestValidatorTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11Z");

    private final AccountTransactionRequestValidator validator =
            new AccountTransactionRequestValidator();

    @Test
    void validateAndNormalize_shouldReturnTransactionData_whenRequestIsValid() {
        TransactionData transaction = validator.validateAndNormalize(
                "acct-1", request("150.00", "usd"));

        assertThat(transaction.eventId()).isEqualTo("evt-1");
        assertThat(transaction.accountId()).isEqualTo("acct-1");
        assertThat(transaction.amount()).isEqualByComparingTo("150.00");
        assertThat(transaction.currency()).isEqualTo("USD");
        assertThat(transaction.eventTimestamp()).isEqualTo(EVENT_TIME);
    }

    @Test
    void validateAndNormalize_shouldRejectAmount_whenMoreThan20IntegerDigits() {
        assertInvalidField(
                "acct-1", request("123456789012345678901", "USD"), "amount");
    }

    @Test
    void validateAndNormalize_shouldRejectAmount_whenMoreThan18FractionalDigits() {
        assertInvalidField(
                "acct-1", request("1.1234567890123456789", "USD"), "amount");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5"})
    void validateAndNormalize_shouldRejectAmount_whenAmountIsNotPositive(String amount) {
        assertInvalidField("acct-1", request(amount, "USD"), "amount");
    }

    @Test
    void validateAndNormalize_shouldAcceptAmount_whenTrailingZeroesAreInsignificant() {
        TransactionData transaction = validator.validateAndNormalize(
                "acct-1", request("0.1000000000000000000", "USD"));

        assertThat(transaction.amount()).isEqualByComparingTo("0.1");
    }

    @Test
    void validateAndNormalize_shouldRejectCurrency_whenCodeIsNotRecognized() {
        assertInvalidField("acct-1", request("150.00", "ABC"), "currency");
    }

    @Test
    void validateAndNormalize_shouldRejectCurrency_whenCodeIsNotThreeLetters() {
        assertInvalidField("acct-1", request("150.00", "US"), "currency");
    }

    @Test
    void validateAndNormalize_shouldRejectIdentifiers_whenIdentifiersAreInvalid() {
        ApplyTransactionRequest invalidRequest = new ApplyTransactionRequest(
                "-bad-event",
                TransactionType.CREDIT,
                new BigDecimal("150.00"),
                "USD",
                EVENT_TIME);

        assertThatThrownBy(() -> validator.validateAndNormalize(
                "-bad-account", invalidRequest))
                .isInstanceOf(AccountRequestValidationException.class)
                .satisfies(thrown -> assertThat(
                        ((AccountRequestValidationException) thrown).getErrors())
                        .extracting("field")
                        .containsExactly("accountId", "eventId"));
    }

    @Test
    void validateAndNormalize_shouldReportRequiredFields_whenRequestFieldsAreMissing() {
        ApplyTransactionRequest incomplete =
                new ApplyTransactionRequest(null, null, null, null, null);

        assertThatThrownBy(() -> validator.validateAndNormalize(null, incomplete))
                .isInstanceOf(AccountRequestValidationException.class)
                .satisfies(thrown -> assertThat(
                        ((AccountRequestValidationException) thrown).getErrors())
                        .extracting("field")
                        .containsExactly(
                                "accountId", "eventId", "type", "amount",
                                "currency", "eventTimestamp"));
    }

    private ApplyTransactionRequest request(String amount, String currency) {
        return new ApplyTransactionRequest(
                "evt-1",
                TransactionType.CREDIT,
                new BigDecimal(amount),
                currency,
                EVENT_TIME);
    }

    private void assertInvalidField(
            String accountId, ApplyTransactionRequest request, String expectedField) {
        assertThatThrownBy(() -> validator.validateAndNormalize(accountId, request))
                .isInstanceOf(AccountRequestValidationException.class)
                .satisfies(thrown -> assertThat(
                        ((AccountRequestValidationException) thrown).getErrors())
                        .extracting("field")
                        .containsExactly(expectedField));
    }
}
