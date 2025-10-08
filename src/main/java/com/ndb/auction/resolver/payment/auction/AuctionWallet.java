package com.ndb.auction.resolver.payment.auction;

import java.util.Locale;
import java.util.Map;

import com.ndb.auction.exceptions.AuctionException;
import com.ndb.auction.exceptions.BidException;
import com.ndb.auction.models.Auction;
import com.ndb.auction.models.Bid;
import com.ndb.auction.models.BidHolding;
import com.ndb.auction.models.user.User;
import com.ndb.auction.resolver.BaseResolver;
import com.ndb.auction.service.user.UserDetailsImpl;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import graphql.kickstart.tools.GraphQLMutationResolver;
import graphql.kickstart.tools.GraphQLQueryResolver;

@Component
public class AuctionWallet extends BaseResolver implements GraphQLMutationResolver, GraphQLQueryResolver {
    // NDB Wallet
	@PreAuthorize("isAuthenticated()")
	public int payWalletForAuction(int roundId, String cryptoType) {
		Auction auction = auctionService.getAuctionById(roundId);
		if(auction == null) {
            String msg = messageSource.getMessage("no_auction", null, Locale.ENGLISH);
			throw new AuctionException(msg, "roundId");
		}
		if(auction.getStatus() != Auction.STARTED) {
            String msg = messageSource.getMessage("not_started", null, Locale.ENGLISH);
			throw new AuctionException(msg, "roundId");
		}

		// Get Bid
		UserDetailsImpl userDetails = (UserDetailsImpl)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        int userId = userDetails.getId();
		
		Bid bid = bidService.getBid(roundId, userId);
		User user = userService.getUserById(userId);
		if(bid == null) {
            String msg = messageSource.getMessage("no_bid", null, Locale.ENGLISH);
			throw new BidException(msg, "roundId");
		} 

		// Get total order in USD
		double totalOrder = 0.0;
		double tierFeeRate = txnFeeService.getFee(user.getTierLevel());
		
		var white = whitelistService.selectByUser(userId);
		if(white != null) tierFeeRate = 0.0;

		if(bid.isPendingIncrease()) {
			double delta = bid.getDelta();
			totalOrder = 100 * delta / (100 - tierFeeRate);
		} else {
			double totalPrice = (double) (bid.getTokenPrice() * bid.getTokenAmount());
			totalOrder = 100 * totalPrice / (100 - tierFeeRate);
		}

		// check crypto Type balance
		double cryptoPrice = thirdAPIUtils.getCryptoPriceBySymbol(cryptoType);
		double cryptoAmount = totalOrder / cryptoPrice; // required amount!
		double freeBalance = internalBalanceService.getFreeBalance(userId, cryptoType);
		if(freeBalance < cryptoAmount) {
            String msg = messageSource.getMessage("insufficient", null, Locale.ENGLISH);
			throw new BidException(msg, "amount");
		} 
			

		// make hold
		internalBalanceService.makeHoldBalance(userId, cryptoType, cryptoAmount);
		
		// update holding list
		Map<String, BidHolding> holdingList = bid.getHoldingList();
		BidHolding hold = null;
		if(holdingList.containsKey(cryptoType)) {
			hold = holdingList.get(cryptoType);
			double currentAmount = hold.getCrypto();
			hold.setCrypto(currentAmount + cryptoAmount);
		} else {
			hold = new BidHolding(cryptoAmount, totalOrder);
			holdingList.put(cryptoType, hold);
		}

		// update bid
		bidService.updateHolding(bid);
		if(bid.isPendingIncrease()) {
			double newAmount = bid.getTempTokenAmount();
			double newPrice = bid.getTempTokenPrice();
			bidService.increaseAmount(userId, roundId, newAmount, newPrice);
	
			// update bid Ranking
			bid.setTokenAmount(newAmount);
			bid.setTokenPrice(newPrice);
		}
		bid.setPayType(Bid.WALLET);
		bidService.updateBidRanking(bid);
		return 1;
	}
}
