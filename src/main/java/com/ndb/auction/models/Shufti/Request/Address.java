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
        // Correct supported types for address verification
        this.supported_types.add("utility_bill"); // Gas, electricity, water, phone bills
        this.supported_types.add("bank_statement"); // Bank account statements
        this.supported_types.add("driving_license"); // Driving license as address proof
        this.supported_types.add("rent_agreement"); // Rental agreements/lease documents
        this.supported_types.add("employer_letter"); // Letter from employer
        this.supported_types.add("tax_bill"); // Tax bills/documents
        this.supported_types.add("insurance_statement"); // Insurance statements
        this.supported_types.add("council_tax"); // Council tax bills
        this.supported_types.add("mortgage_statement"); // Mortgage statements
        this.supported_types.add("tenancy_agreement"); // Tenancy agreements
        this.supported_types.add("credit_card_statement"); // Credit card statements
        this.supported_types.add("pension_statement"); // Pension statements
        this.supported_types.add("telecom_bill"); // Telecom/internet bills
        this.supported_types.add("government_letter"); // Government correspondence
        this.supported_types.add("id_card"); // ID card with address
        this.supported_types.add("passport"); // Passport with address page
    }

    private String proof;
    private List<String> supported_types;
    private Name name;
    private String full_address;
}