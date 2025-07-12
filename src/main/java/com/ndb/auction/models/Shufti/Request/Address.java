package com.ndb.auction.models.Shufti.Request;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Address {

    public Address(String proof, String fullAddress) {
        this.proof = proof;
        this.full_address = fullAddress;
        this.supported_types = new ArrayList<>();
        // ALL valid supported types for address verification according to official
        // Shufti Pro documentation
        // Source: https://developers.shuftipro.com/docs/coverage/documents/
        this.supported_types.add("id_card"); // 1
        this.supported_types.add("passport"); // 2
        this.supported_types.add("driving_license"); // 3
        this.supported_types.add("utility_bill"); // 4
        this.supported_types.add("bank_statement"); // 5
        this.supported_types.add("rent_agreement"); // 6 - This was causing your error!
        this.supported_types.add("employer_letter"); // 7
        this.supported_types.add("insurance_agreement"); // 8
        this.supported_types.add("tax_bill"); // 9
        this.supported_types.add("envelope"); // 10
        this.supported_types.add("cpr_smart_card_reader_copy"); // 11
        this.supported_types.add("property_tax"); // 12
        this.supported_types.add("lease_agreement"); // 13
        this.supported_types.add("insurance_card"); // 14
        this.supported_types.add("permanent_residence_permit"); // 15
        this.supported_types.add("credit_card_statement"); // 16
        this.supported_types.add("insurance_policy"); // 17
        this.supported_types.add("e_commerce_receipt"); // 18
        this.supported_types.add("bank_letter_receipt"); // 19
        this.supported_types.add("birth_certificate"); // 20
        this.supported_types.add("salary_slip"); // 21
        this.supported_types.add("any"); // 22
    }

    private String proof;
    private List<String> supported_types;
    private Name name;
    private String full_address;
}