package com.banking.notificationservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class NotificationService {

    @KafkaListener(topics = "transaction.otp.generated")
    public void consumeOtpGenerated(
            @Payload Map<String,Object> payload) {
        try  {
            String accountNumber = (String) payload.get("accountNumber");
            String otp = (String) payload.get("otp");
            String transactionId = (String) payload.get("transactionId");
            String amount  = payload.get("amount").toString();
            String reason = (String) payload.get("reason");

            sendAlert(accountNumber,
                    "TRANSACTION VERIFICATION REQUIRED",
                    String.format("Suspicious activity detected on your account. Reason: %s. " +
                                    "A transaction of %s is pending verification. Your OTP is %s and is valid for 5 minutes. " +
                                    "If this wasn't you, contact your bank.",
                            reason, amount, otp)
            );
        } catch (Exception e) {
            log.error("Error Sending OTP notification: {}",e.getMessage());
        }
    }

    @KafkaListener(topics = "transaction.completed")
    public void consumeTransactionCompleted (
            @Payload Map<String,Object> payload) {
        try {
            String senderAccount = (String) payload.get("senderAccountNumber");
            String receiverAccount = (String) payload.get("receiverAccountNumber");
            String amount = payload.get("amount").toString();
            // Debit alert
            sendAlert(senderAccount, "Debit Alert",
                    String.format("%s Debited from account %s", amount, senderAccount));
            // Credit alert
            sendAlert(receiverAccount, "Credit Alert",
                    String.format("%s Credited to account %s", amount, receiverAccount));

        } catch (Exception e) {
            log.error("Error Sending transaction notification: {}",e.getMessage());
        }
    }

    @KafkaListener(topics = "fraud.detected")
    public void consumeFraudDetected(
            @Payload Map<String,Object> payload) {
        try {
            String accountNumber = (String) payload.get("accountNumber");
            String reason = (String) payload.get("reason");

            sendAlert(accountNumber, "Suspicious activity detected",
                    String.format("Your account %s has been blocked." + "Reason : %s" + "Please contact your bank immediately.",
                            accountNumber, reason));
        } catch (Exception e) {
            log.error("Error Sending fraud notification: {}",e.getMessage());

        }
    }

    @KafkaListener(topics = "transaction.refunded")
    public void consumeTransactionRefunded(
            @Payload Map<String,Object> payload) {
        try {
            String senderAccount = (String) payload.get("senderAccountNumber");
            String amount = payload.get("amount").toString();
            String reason = (String) payload.get("reason");

            sendAlert(senderAccount,
                    "REFUNDED PROCESSED",
                    String.format("Your transaction of %s was cancelled." +
                            "Reason : %s "+
                            "%s has been refunded to account %s.", amount, reason,amount, senderAccount));
        } catch (Exception e) {
            log.error("Error Sending refunded notification: {}",e.getMessage());
        }
    }

    private void  sendAlert(String accountNumber, String subject, String message) {

        log.info("-----------------------------------");
        log.info("Account number: {}",accountNumber);
        log.info("Subject: {}",subject);
        log.info("Message: {}",message);
        log.info("-----------------------------------");
    }

}
