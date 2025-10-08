package com.ndb.auction.resolver;

import java.util.List;
import java.util.Optional;

import com.ndb.auction.models.presale.PreSale;
import com.ndb.auction.models.presale.PreSaleCondition;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import graphql.kickstart.tools.GraphQLMutationResolver;
import graphql.kickstart.tools.GraphQLQueryResolver;

@Component
public class PresaleResolver extends BaseResolver implements GraphQLQueryResolver, GraphQLMutationResolver{
    
    // create new presale round
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public int createNewPresale(
        Long startedAt,
        Long endedAt,
        Double tokenAmount,
        Double tokenPrice,
        List<PreSaleCondition> conditions
    ) {
        int lastAuctionRound = auctionService.getNewRound();
        int lastPresaleRound = presaleService.getNewRound();
        int round = lastPresaleRound;
        if (Optional.ofNullable(lastAuctionRound).orElse(0) == 0)
            round= lastAuctionRound > lastPresaleRound ? lastAuctionRound : lastPresaleRound;

        PreSale presale = new PreSale(round, startedAt, endedAt, tokenAmount, tokenPrice, conditions);
        return presaleService.createNewPresale(presale);
    }

    @PreAuthorize("isAuthenticated()")
    public List<PreSale> getPreSales() {
        return presaleService.getPresales();
    }

    @PreAuthorize("isAuthenticated()")
    public List<PreSale> getPreSaleByStatus(int status) {
        List<PreSale> presales = presaleService.getPresaleByStatus(status);
        for (PreSale preSale : presales) {
            List<PreSaleCondition> conditions = presaleService.getConditionsById(preSale.getId());
            preSale.setConditions(conditions);
        }   
        return presales;
    }

    

}
