package com.ndb.auction.resolver;

import com.ndb.auction.exceptions.PreSaleException;
import com.ndb.auction.exceptions.UnauthorizedException;
import com.ndb.auction.exceptions.UserSuspendedException;
import com.ndb.auction.models.presale.PreSale;
import com.ndb.auction.models.presale.PreSaleOrder;
import com.ndb.auction.models.presale.PresaleOrderPayments;
import com.ndb.auction.service.user.UserDetailsImpl;
import com.ndb.auction.service.user.UserReferralService;
import com.ndb.auction.utils.Utilities;
import com.ndb.auction.web3.NyyuWalletService;
import graphql.kickstart.tools.GraphQLMutationResolver;
import graphql.kickstart.tools.GraphQLQueryResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class PresaleOrderResolver extends BaseResolver implements GraphQLQueryResolver, GraphQLMutationResolver {
    
    private final double MINIMUM_PURCHASE_NDB = 50;

    @Autowired
    private UserReferralService userReferralService;

    @Autowired
    private NyyuWalletService nyyuWalletService;
    
    /*
     * @params
     * destination: 1 - internal wallet, 2 - external wallet
     */
    @PreAuthorize("isAuthenticated()")
    public PreSaleOrder placePreSaleOrder(int presaleId, Long ndbAmount, int destination, String extAddr) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        int userId = userDetails.getId();
        if (userService.isUserSuspended(userId)) {
            String msg = messageSource.getMessage("user_suspended", null, Locale.ENGLISH);
            throw new UserSuspendedException(msg);
        }

        var kycStatus = shuftiService.kycStatusCkeck(userId);
        if (!kycStatus) {
            String msg = messageSource.getMessage("no_kyc", null, Locale.ENGLISH);
            throw new UnauthorizedException(msg, "userId");
        }

        // check presale status
        PreSale presale = presaleService.getPresaleById(presaleId);
        if (presale == null) {
            String msg = messageSource.getMessage("no_presale", null, Locale.ENGLISH);
            throw new PreSaleException(msg, "presaleId");
        }

        // check minimum purchase limit
        double _ndbAmount = (double)ndbAmount;
        if(_ndbAmount * presale.getTokenPrice() < MINIMUM_PURCHASE_NDB) {
            String msg = messageSource.getMessage("presale_purchase_limit", null, Locale.ENGLISH);
            throw new PreSaleException(msg, "ndbAmount");
        }

        // check NDB amount
        double remain = presale.getTokenAmount() - presale.getSold();
        
        if(ndbAmount > remain) {
            String msg = messageSource.getMessage("presale_amount_overflow", null, Locale.ENGLISH);
            throw new PreSaleException(msg, "ndbAmount");
        }        

        if(presale.getStatus() != PreSale.STARTED) {
            String msg = messageSource.getMessage("not_started", null, Locale.ENGLISH);
            throw new PreSaleException(msg, "presaleId");
        }

        // check timelock if target wallet is changed
        // it is applied only for invited users
        var referral = userReferralService.selectById(userId);
        if(referral != null && referral.getReferredByCode() != null) {
            
            // check destination wallet
            if(referral.getTarget() != destination) {
                // check timelock
                int lockedTime = userReferralService.checkTimeLock(userId);

                if(lockedTime > 0) {
                    // throw wallet exception
                    var formattedTime = Utilities.lockTimeFormat(lockedTime);
                    throw new PreSaleException("You can change destination wallet after " + formattedTime, "destination");
                }

                // change wallet
                var currentAddr = referral.getWalletConnect();
                var newAddr = extAddr;
                if(destination == 1) {
                    // get Nyyu wallet
                    var nyyuWallet = nyyuWalletService.selectByUserId(userId, "BEP20");
                    newAddr = nyyuWallet.getPublicKey();
                } 
                userReferralService.changeReferralWallet(userId, destination, currentAddr, newAddr);
            }
        }

        // check nyyu wallet 
        String targetAddress = "";
        if(destination == PreSaleOrder.INTERNAL) {
            // for the old users
            var nyyuWallet = nyyuWalletService.selectByUserId(userId, "BEP20");
            if(nyyuWallet == null) {
                var generatedAddr = nyyuWalletService.generateNyyuWallet("BEP20", userId);
                if(generatedAddr == null) {
                    throw new PreSaleException("You cannot place a presale order because of Nyyu internal wallet", "destination");
                }
                targetAddress = generatedAddr;
            } else {
                targetAddress = nyyuWallet.getPublicKey();
            }
        } else {
            targetAddress = extAddr;
        }

        

        // create new Presale order
        Double ndbPrice = presale.getTokenPrice();
        PreSaleOrder presaleOrder = new PreSaleOrder(userId, presaleId, ndbAmount, ndbPrice, destination, targetAddress);
        return presaleOrderService.placePresaleOrder(presaleOrder);
    }

    @PreAuthorize("isAuthenticated()")
    public List<PreSaleOrder> getPresaleOrders(int presaleId) {
        return presaleOrderService.getPresaleOrders(presaleId);
    }

    @PreAuthorize("isAuthenticated()")
    public List<PreSaleOrder> getNewPresaleOrders(int presaleId, int lastOrderId) {
        return presaleOrderService.getPresaleOrders(presaleId, lastOrderId);
    }

    @PreAuthorize("isAuthenticated()")
    public List<PreSaleOrder> getPresaleOrdersByUser() {
        UserDetailsImpl userDetails = (UserDetailsImpl)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        int userId = userDetails.getId();
        return presaleOrderService.getPresaleOrdersByUserId(userId);
    }

    @PreAuthorize("isAuthenticated()")
    public PreSaleOrder getPresaleById(int id) {
        return presaleOrderService.getPresaleById(id);
    }

    @PreAuthorize("isAuthenticated()")
    public PresaleOrderPayments getPresaleOrderTransactions(int orderId) {
        UserDetailsImpl userDetails = (UserDetailsImpl)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        int userId = userDetails.getId();

        return presaleOrderService.getPaymentsByOrder(userId, orderId);
    }

}
