package com.banking.transactionservice.service;

import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import com.banking.transactionservice.event.TransactionCompletedEvent;
import com.banking.transactionservice.event.TransactionInitiatedEvent;
import com.banking.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;

    private final KafkaTemplate<String,Object> kafkaTemplate;
    private final RedisTemplate<String,String> redisTemplate;

    private static final String TRANSACTION_INITIATED_TOPIC = "transaction.initiated";
    private static final String TRANSACTION_COMPLETED_TOPIC = "transaction.completed";
    private static final String TRANSACTION_REFUNDED_TOPIC = "transaction.refunded";
    private static final String FRAUD_DETECTED_TOPIC = "fraud.detected";

    /*
    Saga step 1
    initiate transfer
    deduct from sender via feign
    saves transaction as processing
    publish event to kafka for fraud check
     */
    public TransactionResponse transfer (TransferRequest request) {

        log.info("SAGA start - transfer : {} => {} amount {}", request.getSenderAccountNumber(),
                request.getReceiverAccountNumber(), request.getAmount());

        // Saga step 1 : Deduct from sender
        accountServiceClient.deductBalance(
                request.getSenderAccountNumber(),
                request.getAmount()
        );
        Transaction transaction = new Transaction();
        transaction.setSenderAccountNumber(request.getSenderAccountNumber());
        transaction.setReceiverAccountNumber(request.getReceiverAccountNumber());
        transaction.setAmount(request.getAmount());
        transaction.setTransactionType(TransactionType.TRANSFER);
        transaction.setTransactionStatus(TransactionStatus.PROCESSING);
        transaction.setDescription(request.getDescription());
        transaction.setReferenceNumber(UUID.randomUUID().toString());

        Transaction savedTransaction = transactionRepository.save(transaction);

        log.info("Transaction saved as PROCESSING : {}", savedTransaction.getId());

        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                savedTransaction.getId(),
                savedTransaction.getSenderAccountNumber(),
                savedTransaction.getReceiverAccountNumber(),
                savedTransaction.getAmount(),
                savedTransaction.getDescription()
        );

        kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC, savedTransaction.getId(), event);
        log.info("SAGA step 2 Transaction initiated event published : {}", savedTransaction.getId());
        return mapToResponse(savedTransaction);
    }

    public TransactionResponse getTransaction (String transactionId) {
        return mapToResponse(transactionRepository.findById(transactionId).
                orElseThrow(() -> new RuntimeException("Transaction Not Found : "+ transactionId)));
    }

    public List<TransactionResponse> getTransactionHistory(String accountNumber) {
        return transactionRepository.findBySenderAccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public TransactionResponse verifyOTP (String transactionId, String otp) {
        log.info("OTP verification for the transaction : {}", transactionId);

        Transaction transaction = transactionRepository.findById(transactionId).
                orElseThrow(()  -> new RuntimeException("Transaction Not Found : "+ transactionId));
        String otpKey = "verification:otp" + transactionId;
        String storedOTP = redisTemplate.opsForValue().get(otpKey);

        if (storedOTP == null) {
            log.warn("OTP expired for transaction : {}", transactionId);
            compensateTransaction(transaction, "OTP expired - transaction cancelled and amount refunded");
            return mapToResponse(transaction);
        }

        if (!storedOTP.equals(otp)) {
            log.warn("Wrong OTP - blocking account and refunded : {}", transactionId);
            redisTemplate.delete(otpKey);
            blockAccountAndCompensate(transaction, "wrong OTP entered - transaction cancelled, " + "account blocked for security");
            return mapToResponse(transaction);
        }

        log.info("OTP verified successfully for transaction : {}", transactionId);
        redisTemplate.delete(otpKey);
        completeTransaction(transaction);
        return mapToResponse(transaction);
    }

    private void compensateTransaction(Transaction transaction, String reason) {
        log.warn("Saga compensation - refunding : {} amount: {}",transaction.getSenderAccountNumber(), transaction.getAmount());
        // credit money back to sender synchronously
        accountServiceClient.creditBalance(transaction.getSenderAccountNumber(), transaction.getAmount());
        transaction.setTransactionStatus(TransactionStatus.FLAGGED);
        transaction.setFailureReason(reason+" - Saga compensation executed, amount refunded at " + LocalDateTime.now());
        transactionRepository.save(transaction);

        //Publish refund event - notification service will alert user
        Map<String, Object> refundEvent = new HashMap<>();
        refundEvent.put("transactionId", transaction.getId());
        refundEvent.put("sender Account Number", transaction.getSenderAccountNumber());
        refundEvent.put("amount", transaction.getAmount());
        refundEvent.put("reason", reason);

        kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC, transaction.getId() ,refundEvent);

        log.info("Saga compensation completed {} refunded to {}", transaction.getAmount(), transaction.getId());
    }

    private void blockAccountAndCompensate(Transaction transaction, String reason) {
        // publish fraud get detected and account service will block the account

        Map<String, Object> fraudEvent = new HashMap<>();
        fraudEvent.put("transactionId", transaction.getId());
        fraudEvent.put("account Number", transaction.getSenderAccountNumber());
        fraudEvent.put("reason", reason);

        kafkaTemplate.send(FRAUD_DETECTED_TOPIC, transaction.getSenderAccountNumber(), fraudEvent);
        log.warn("fraud.detected published - account: {} will be blocked, kindly contact to you bank", transaction.getSenderAccountNumber());
        // saga compensation and refund sender
        compensateTransaction(transaction, reason);
    }

    private void completeTransaction (Transaction transaction) {
        transaction.setTransactionStatus(TransactionStatus.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());
        transactionRepository.save(transaction);

        TransactionCompletedEvent completedEvent = new TransactionCompletedEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                transaction.getReceiverAccountNumber(),
                transaction.getAmount(),
                transaction.getDescription()
        );

        kafkaTemplate.send(TRANSACTION_COMPLETED_TOPIC, transaction.getId(), completedEvent);
        log.info("Saga Completed transaction : {}", transaction.getId());
    }

    public void processCleanResult (String transactionId) {

        Transaction transaction = transactionRepository.findById(transactionId).
                orElseThrow(()  -> new RuntimeException("Transaction Not Found : "+ transactionId));
        if (transaction.getTransactionStatus() != TransactionStatus.PROCESSING) {
            log.warn("Transaction {} not processing - skipping ", transactionId);
            return;
        }
        completeTransaction(transaction);
    }

    private TransactionResponse mapToResponse(Transaction transaction) {
        TransactionResponse response = new TransactionResponse();
        response.setId(transaction.getId());
        response.setSenderAccountNumber(transaction.getSenderAccountNumber());
        response.setReceiverAccountNumber(transaction.getReceiverAccountNumber());
        response.setAmount(transaction.getAmount());
        response.setTransactionType(transaction.getTransactionType());
        response.setTransactionStatus(transaction.getTransactionStatus());
        response.setDescription(transaction.getDescription());
        response.setReferenceNumber(transaction.getReferenceNumber());
        response.setFailureReason(transaction.getFailureReason());
        response.setCreatedAt(transaction.getCreatedAt());
        response.setCompletedAt(transaction.getCompletedAt());

        return response;
    }
}
