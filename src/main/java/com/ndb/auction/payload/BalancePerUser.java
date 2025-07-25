package com.ndb.auction.payload;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BalancePerUser {
    
    private int UserId;
    private String email;
    private String avatarName;
    private List<BalancePayload> balances;
    
}
