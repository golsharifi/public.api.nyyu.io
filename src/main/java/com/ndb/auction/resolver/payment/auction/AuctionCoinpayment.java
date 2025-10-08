package com.ndb.auction.resolver.payment.auction;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.expression.ParseException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.ndb.auction.exceptions.UnauthorizedException;
import com.ndb.auction.models.transactions.coinpayment.CoinpaymentDepositTransaction;
import com.ndb.auction.resolver.BaseResolver;
import com.ndb.auction.service.payment.coinpayment.CoinpaymentAuctionService;
import com.ndb.auction.service.user.UserDetailsImpl;

import graphql.kickstart.tools.GraphQLMutationResolver;
import graphql.kickstart.tools.GraphQLQueryResolver;

@Component
public class AuctionCoinpayment extends BaseResolver implements GraphQLMutationResolver, GraphQLQueryResolver {

	@Autowired
	protected CoinpaymentAuctionService coinpaymentAuctionService;

	private static final String AUCTION = "AUCTION";

	// for Coinpayments
	@PreAuthorize("isAuthenticated()")
	public CoinpaymentDepositTransaction createCryptoPaymentForAuction(int roundId, String cryptoType, String network,
			String coin) throws ParseException, IOException {
		UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
				.getPrincipal();
		int userId = userDetails.getId();

		var bid = bidService.getBid(roundId, userId);

		// check pending price
		double orderAmount = 0.0;
		if (bid.isPendingIncrease()) {
			orderAmount = bid.getTempTokenAmount() * bid.getTempTokenPrice()
					- bid.getTokenAmount() * bid.getTokenPrice();
		} else {
			orderAmount = bid.getTokenAmount() * bid.getTokenPrice();
		}
		var cryptoPrice = thirdAPIUtils.getCryptoPriceBySymbol(cryptoType);
		var cryptoAmount = orderAmount / cryptoPrice;
		double total = getTotalCoinpaymentOrder(userId, cryptoAmount);
		CoinpaymentDepositTransaction _m = new CoinpaymentDepositTransaction(roundId, userId, orderAmount, cryptoAmount,
				total - cryptoAmount, AUCTION, cryptoType, network, coin);
		return coinpaymentAuctionService.createNewTransaction(_m);
	}

	public String getExchangeRate() throws ParseException, IOException {
		return coinpaymentAuctionService.getExchangeRate();
	}

	@PreAuthorize("hasRole('ROLE_SUPER')")
	public CoinpaymentDepositTransaction getCryptoAuctionTxById(int id) {
		var m = coinpaymentAuctionService.selectById(id);

		// Add null check
		if (m == null) {
			throw new UnauthorizedException("Transaction not found.", "id");
		}

		return m;
	}

	@PreAuthorize("hasRole('ROLE_SUPER')")
	public List<CoinpaymentDepositTransaction> getCryptoAuctionTxByAdmin(int userId) {
		return coinpaymentAuctionService.selectByUser(userId, 1, AUCTION);
	}

	@PreAuthorize("isAuthenticated()")
	public List<CoinpaymentDepositTransaction> getCryptoAuctionTxByUser() {
		UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
				.getPrincipal();
		int userId = userDetails.getId();
		return coinpaymentAuctionService.selectByUser(userId, 1, AUCTION);
	}

	@PreAuthorize("hasRole('ROLE_SUPER')")
	public List<CoinpaymentDepositTransaction> getCryptoAuctionTxByRound(int roundId) {
		return coinpaymentAuctionService.selectByOrderId(roundId, AUCTION);
	}

	@PreAuthorize("hasRole('ROLE_SUPER')")
	public List<CoinpaymentDepositTransaction> getCryptoAuctionTxPerRoundByAdmin(int roundId, int userId) {
		return coinpaymentAuctionService.selectByOrderIdByUser(userId, roundId, AUCTION);
	}

	@PreAuthorize("isAuthenticated()")
	public List<CoinpaymentDepositTransaction> getCryptoAuctionTx(int roundId) {
		UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
				.getPrincipal();
		int userId = userDetails.getId();
		return coinpaymentAuctionService.selectByOrderIdByUser(userId, roundId, AUCTION);
	}

	@PreAuthorize("isAuthenticated()")
	public double getCryptoPrice(String symbol) {
		return thirdAPIUtils.getCryptoPriceBySymbol(symbol);
	}

	@PreAuthorize("isAuthenticated()")
	public int updateCoinpaymentTxHash(int id, String txHash) {
		return coinpaymentAuctionService.updateTxHash(id, txHash);
	}
}
