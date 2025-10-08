package com.ndb.auction.models.tier;

import com.ndb.auction.models.BaseModel;

import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Component
@Getter
@Setter
@NoArgsConstructor
public class Tier extends BaseModel {

    private int level;
    private String name;
    private Long point;
    private String svg;

    public Tier(int level, String name, Long point, String svg) {
        this.level = level;
        this.name = name;
        this.point = point;
        this.svg = svg;
    }

}
