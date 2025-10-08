package com.ndb.auction.service.utils;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import com.ndb.auction.dao.oracle.balance.CryptoBalanceDao;
import com.ndb.auction.dao.oracle.user.UserDetailDao;
import com.ndb.auction.models.user.User;
import com.ndb.auction.payload.BankMeta;
import com.ndb.auction.payload.RecoveryRequest;
import com.ndb.auction.payload.WithdrawRequest;
import com.ndb.auction.service.TokenAssetService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import freemarker.core.ParseException;
import freemarker.template.Configuration;
import freemarker.template.MalformedTemplateNameException;
import freemarker.template.TemplateException;
import freemarker.template.TemplateNotFoundException;

@Service
public class MailService {

    private JavaMailSender javaMailSender;

    private final Configuration configuration;

    @Autowired
    private UserDetailDao userDetailDao;

    @Autowired
    private TokenAssetService tokenAssetService;

    @Autowired
    private CryptoBalanceDao balanceDao;

    @Autowired
    public MailService(Configuration configuration, JavaMailSender javaMailSender) {
        this.configuration = configuration;
        this.javaMailSender = javaMailSender;
    }

    public void sendVerifyEmail(User user, String code, String template)
            throws MessagingException, IOException, TemplateException {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage);
        helper.setSubject("Nyyu Account Verification");
        helper.setTo(user.getEmail());
        String emailContent = getEmailContent(user, code, template);
        helper.setText(emailContent, true);
        javaMailSender.send(mimeMessage);
    }

    private String getEmailContent(User user, String code, String template) throws IOException, TemplateException {
        StringWriter stringWriter = new StringWriter();
        Map<String, Object> model = new HashMap<>();
        // model.put("user", user);
        model.put("code", code);
        configuration.getTemplate(template).process(model, stringWriter);
        return stringWriter.getBuffer().toString();
    }

    public void sendNormalEmail(User user, String subject, String text)
            throws MessagingException, IOException, TemplateException {

        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage);
        helper.setSubject(subject);
        helper.setTo(user.getEmail());
        String emailContent = getEmailContent(user, text, "AlertEmail.ftlh");
        helper.setText(emailContent, true);
        javaMailSender.send(mimeMessage);
    }

    public void sendBackupEmail(List<User> users, String... paths) throws MessagingException {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
        helper.setSubject("Backup report");
        helper.setText("Backed up database tables", true);
        for (var user : users) {
            helper.addTo(user.getEmail());
        }

        for (var path : paths) {
            var file = new java.io.File(path);
            helper.addAttachment(path, file);
        }
        javaMailSender.send(mimeMessage);
    }

    private String fillWithdrawRequestEmail(String template, WithdrawRequest contents) throws TemplateNotFoundException,
            MalformedTemplateNameException, ParseException, TemplateException, IOException {
        StringWriter stringWriter = new StringWriter();
        Map<String, Object> model = new HashMap<>();
        model.put("withdrawType", contents.getWithdrawType());
        model.put("avatarName", contents.getAvatarName());
        model.put("email", contents.getEmail());
        model.put("fullName", contents.getFullName());
        model.put("address", contents.getAddress());
        model.put("country", contents.getCountry());
        model.put("balance", String.format("%.8f", contents.getBalance()));
        model.put("sourceToken", contents.getCurrency());
        model.put("requestAmount", String.format("%.8f", contents.getRequestAmount()));
        model.put("requestCurrency", contents.getRequestCurrency());
        model.put("typeMessage", contents.getTypeMessage());
        model.put("destination", contents.getDestination());
        model.put("bankMeta", contents.getBankMeta());
        configuration.getTemplate(template).process(model, stringWriter);
        return stringWriter.getBuffer().toString();
    }

    public void sendWithdrawRequestNotifyEmail(
            List<User> superUsers, User requester, String type, String currency, double withdrawAmount,
            String withdrawCurrency, String destination, BankMeta bankMeta) throws MessagingException,
            TemplateNotFoundException, MalformedTemplateNameException, ParseException, TemplateException, IOException {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage);
        helper.setSubject("Withdraw Request");

        // getting required information
        String avatarName = requester.getAvatar().getPrefix() + "." + requester.getAvatar().getName();
        var userDetail = userDetailDao.selectByUserId(requester.getId());
        var fullName = "";
        var address = "";
        var country = "";
        if (userDetail != null) {
            fullName = userDetail.getFirstName() + " " + userDetail.getLastName();
            address = userDetail.getAddress();
            var tempList = userDetail.getAddress().split(",");
            country = tempList[tempList.length - 1].substring(1);
        }

        var tokenId = tokenAssetService.getTokenIdBySymbol(currency);
        var balance = balanceDao.selectById(requester.getId(), tokenId).getFree();

        // withdarw type message
        String typeMessage = "";
        if (type.equals("PayPal")) {
            typeMessage = "PayPal email";
        } else if (type.equals("Crypto")) {
            typeMessage = "Wallet address";
        } else if (type.equals("Bank")) {
            typeMessage = "Account Details";
        }

        // build withdraw request
        var withdrawRequest = new WithdrawRequest(
                type, avatarName, requester.getEmail(), fullName, address, country, balance, currency,
                withdrawAmount, withdrawCurrency, typeMessage, destination, bankMeta);

        helper.setText(fillWithdrawRequestEmail("withdrawRequest.ftlh", withdrawRequest), true);
        for (var user : superUsers) {
            helper.addTo(user.getEmail());
        }
        javaMailSender.send(mimeMessage);
    }

    public String fillRecoveryEmailContent(RecoveryRequest request) throws TemplateNotFoundException,
            MalformedTemplateNameException, ParseException, TemplateException, IOException {
        StringWriter stringWriter = new StringWriter();
        var model = new HashMap<String, Object>();
        // destructure
        model.put("email", request.getUser().getEmail());
        model.put("prefix", request.getUser().getAvatar().getPrefix());
        model.put("name", request.getUser().getAvatar().getName());
        model.put("coin", request.getCoin());
        model.put("address", request.getReceiverAddr());
        model.put("txId", request.getTxId());
        model.put("depositAmount", request.getDepositAmount());

        configuration.getTemplate("recovery.ftlh").process(model, stringWriter);
        return stringWriter.getBuffer().toString();
    }

    public void sendRecoveryEmail(RecoveryRequest request) throws MessagingException, TemplateNotFoundException,
            MalformedTemplateNameException, ParseException, TemplateException, IOException {
        var mimeMessage = javaMailSender.createMimeMessage();
        var helper = new MimeMessageHelper(mimeMessage);
        helper.setSubject("Recovery Request");
        String emailContent = fillRecoveryEmailContent(request);
        helper.setText(emailContent, true);
        helper.addTo("info@ndb.money");
        javaMailSender.send(mimeMessage);
    }

    public void sendPurchase(int round, String email, String avatarName, String gateway, String fiatType, double ndb,
            double paid, String dest, String destAddr, List<User> admins) throws TemplateNotFoundException,
            MalformedTemplateNameException, ParseException, TemplateException, IOException, MessagingException {
        // fill mail content
        var stringWriter = new StringWriter();
        var model = new HashMap<String, Object>();

        model.put("admin", "ADMIN");
        model.put("round", round);
        model.put("avatarName", avatarName);
        model.put("email", email);
        model.put("ndbAmount", ndb);
        model.put("paid", paid);
        model.put("fiatType", fiatType);
        model.put("payType", gateway);
        model.put("dest", dest);
        model.put("destAddr", destAddr);

        configuration.getTemplate("presale.ftlh").process(model, stringWriter);
        var mailContents = stringWriter.getBuffer().toString();

        // send
        var mimeMessage = javaMailSender.createMimeMessage();
        var helper = new MimeMessageHelper(mimeMessage);
        helper.setSubject("Presale Purchase");
        helper.setText(mailContents, true);
        for (User admin : admins) {
            helper.addTo(admin.getEmail());
        }
        javaMailSender.send(mimeMessage);
    }

    public void sendDeposit(String email, String avatarName, String gateway, String paidType, String depositType,
            double paid, double deposited, double fee, List<User> admins)
            throws TemplateNotFoundException, MalformedTemplateNameException, ParseException, IOException,
            MessagingException, TemplateException {
        var stringWriter = new StringWriter();
        var model = new HashMap<String, Object>();

        model.put("avatarName", avatarName);
        model.put("email", email);
        model.put("currency", paidType);
        model.put("depositType", depositType);
        model.put("payType", gateway);
        model.put("paid", paid);
        model.put("deposit", deposited);
        model.put("fee", fee);

        configuration.getTemplate("deposit.ftlh").process(model, stringWriter);
        var mailContents = stringWriter.getBuffer().toString();

        var mimeMessage = javaMailSender.createMimeMessage();
        var helper = new MimeMessageHelper(mimeMessage);
        helper.setSubject("Deposit confirmed");
        helper.setText(mailContents, true);

        for (User user : admins) {
            helper.addTo(user.getEmail());
        }
        javaMailSender.send(mimeMessage);
    }
}
