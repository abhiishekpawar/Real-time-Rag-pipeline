package com.rag.demo.service;

import com.demo.schema.UserQuestion;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaProducerService {

    private final KafkaTemplate<String, UserQuestion> kafkaTemplate;

    public KafkaProducerService(KafkaTemplate<String, UserQuestion> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendQuestion(String question) {

        UserQuestion record = UserQuestion.newBuilder().setQuestion(question).build();

        kafkaTemplate.send("user_questions", record);
    }
}
