package com.ndb.auction.models.presale;

import java.util.List;

import com.ndb.auction.models.BaseModel;

import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Component
@Getter
@Setter
@NoArgsConstructor
public class PreSale extends BaseModel {

    public static int PENDING = 0;
    public static int COUNTDOWN = 1;
    public static int STARTED = 2;
    public static int ENDED = 3;

    private int round;

    private Long startedAt;
    private Long endedAt;

    private Double tokenAmount;
    private Double tokenPrice;
    private Double sold;

    private int status;
    private int kind;

    private List<PreSaleCondition> conditions;

    public PreSale(
            int round,
            Long startedAt,
            Long endedAt,
            Double tokenAmount,
            Double tokenPrice,
            List<PreSaleCondition> conditions) {
        this.round = round;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.tokenAmount = tokenAmount;
        this.tokenPrice = tokenPrice;
        this.conditions = conditions;
        this.sold = 0.0;
        this.status = COUNTDOWN;
    }

}
