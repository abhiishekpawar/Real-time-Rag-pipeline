package com.rag.demo.controller;

import com.demo.schema.UserQuestion;
import com.rag.demo.service.KafkaProducerService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/kafka")
public class KafkaController {

    private final KafkaProducerService producerService;

    public KafkaController(KafkaProducerService producerService) {
        this.producerService = producerService;
    }

    @PostMapping("/send")
    public String send(@RequestBody UserQuestion request) {

        producerService.sendQuestion(request.getQuestion().toString());

        System.out.println("==============================================================");
        System.out.println("Produced event: "+ request);
        System.out.println("==============================================================");

        return "Message sent to Kafka";
    }
}
