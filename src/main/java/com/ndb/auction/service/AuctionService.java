package com.ndb.auction.service;

import java.util.List;
import java.util.Locale;

import com.ndb.auction.exceptions.BalanceException;
import com.ndb.auction.models.Auction;
import com.ndb.auction.models.Notification;
import com.ndb.auction.models.avatar.AvatarSet;
import com.ndb.auction.models.presale.PreSale;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuctionService extends BaseService {

	// @PostConstruct
	// public void init() {
	// 	schedule.checkAllRounds();
	// }

	public Auction createNewAuction(Auction auction) {

		// Started at checking
		if (System.currentTimeMillis() > auction.getStartedAt()) {
			System.out.println(System.currentTimeMillis());
			String msg = messageSource.getMessage("invalid_time", null, Locale.ENGLISH);
            throw new BalanceException(msg, "startedAt");
		}

		// check conflict auction round
		Auction _auction = auctionDao.getAuctionByRound(auction.getRound());
		if (_auction != null) {
			String msg = messageSource.getMessage("already_in_action", null, Locale.ENGLISH);
            throw new BalanceException(msg, "roundId");
		}

		// started round
		List<Auction> auctions = auctionDao.getAuctionByStatus(Auction.COUNTDOWN);
		if(auctions.size() != 0) {
			String msg = messageSource.getMessage("already_in_action", null, Locale.ENGLISH);
            throw new BalanceException(msg, "roundId");
		}		
		auctions = auctionDao.getAuctionByStatus(Auction.STARTED);
		if(auctions.size() != 0) {
			String msg = messageSource.getMessage("already_in_action", null, Locale.ENGLISH);
            throw new BalanceException(msg, "roundId");
		}		

		// check presale
		List<PreSale> presales = presaleDao.selectByStatus(PreSale.COUNTDOWN);
		if(presales.size() != 0) {
			String msg = messageSource.getMessage("already_in_action", null, Locale.ENGLISH);
            throw new BalanceException(msg, "roundId");
		}
		presales = presaleDao.selectByStatus(PreSale.STARTED);
		if(presales.size() != 0) {
			String msg = messageSource.getMessage("already_in_action", null, Locale.ENGLISH);
            throw new BalanceException(msg, "roundId");
		}

		auction = auctionDao.createNewAuction(auction);
		// add auction avatar
		for (AvatarSet avatarSet : auction.getAvatar()) {
			avatarSet.setId(auction.getId());
			auctionAvatarDao.insert(avatarSet);
		}
		schedule.setNewCountdown(auction);
		return auction;
	}

	public List<Auction> getAuctionList() {
		List<Auction> auctionList = auctionDao.getAuctionList();
		for (Auction auction : auctionList) {
			auction.setAvatar(auctionAvatarDao.selectById(auction.getId()));
		}
		return auctionList;
	}

	public Auction getAuctionById(int id) {
		Auction auction = auctionDao.getAuctionById(id);
		if(auction == null) return null;
		auction.setAvatar(auctionAvatarDao.selectById(auction.getId()));
		return auction;
	}

	public Auction getAuctionByRound(int round) {
		Auction auction = auctionDao.getAuctionByRound(round);
		if(auction == null) return null;
		auction.setAvatar(auctionAvatarDao.selectById(auction.getId()));
		return auction;
	}

	public Auction updateAuctionByAdmin(Auction auction) {

		// Check Validation ( null possible )
		Auction _auction = auctionDao.getAuctionById(auction.getId());
		if (_auction == null)
			return null;
		if (_auction.getStatus() != Auction.PENDING)
		{
			String msg = messageSource.getMessage("already_in_action", null, Locale.ENGLISH);
            throw new BalanceException(msg, "roundId");
		}

		auctionDao.updateAuctionByAdmin(auction);
		auctionAvatarDao.update(auction.getId(), auction.getAvatar());
		return auction;
	}

	public void startAuction(int id) {

		// check already opened Round
		List<Auction> list = auctionDao.getAuctionByStatus(Auction.STARTED);
		if (list.size() != 0) {
			String msg = messageSource.getMessage("already_in_action", null, Locale.ENGLISH);
            throw new BalanceException(msg, "roundId");
		}

		// check current auction is pending
		Auction target = auctionDao.getAuctionById(id);
		if (target.getStatus() != Auction.COUNTDOWN) {
			String msg = messageSource.getMessage("already_in_action", null, Locale.ENGLISH);
            throw new BalanceException(msg, "roundId");
		}

		auctionDao.startAuction(target);

		notificationService.broadcastNotification(
			Notification.NEW_ROUND_STARTED, 
			"NEW ROUND STARTED", 
			"Auction round number " + target.getRound() + " just started.");

	}

	public Auction endAuction(int id) {
		// check Auction is Started!
		Auction target = auctionDao.getAuctionById(id);
		if (target.getStatus() != Auction.STARTED) {
			return null; // or exception
		}
		auctionDao.endAuction(target);

		String msg = String.format("Auction round number %d just finished.", target.getRound());
		String title = "ROUND FINISHED";
		notificationService.broadcastNotification(Notification.ROUND_FINISHED, title, msg);

		return target;
	}

	public List<Auction> getAuctionByStatus(Integer status) {
		List<Auction> auctionList = auctionDao.getAuctionByStatus(status);
		for (Auction auction : auctionList) {
			auction.setAvatar(auctionAvatarDao.selectById(auction.getId()));
		}
		return auctionList;
	}

	public String checkRounds() {
		schedule.checkAllRounds();
		return "Checked";
	}

	public int getNewRound() {
		return auctionDao.getNewRound();
	}

	public int getLastAuction() {
		return auctionDao.getCountRounds();
	}

}
