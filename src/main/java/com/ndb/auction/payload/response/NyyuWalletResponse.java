package com.ndb.auction.payload.response;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NyyuWalletResponse {
    private String address;
    private String error;
    private String status;
    private Object data;

    public boolean hasError() {
        return error != null && !error.isEmpty();
    }
}