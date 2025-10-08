package com.ndb.auction.service.utils;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;

import freemarker.template.Configuration;
import freemarker.template.TemplateException;

@Service
public class SMSService {
	
	@Value("${twilio.sid}")
	private String sid;

	@Value("${twilio.token}")
	private String token;
	
	@Value("${twilio.phone}")
	private String phone;

	private static final String TEMPLATE = "2faSMS.ftlh";
	
	private final Configuration configuration;
	
	@Autowired
	public SMSService(Configuration configuration) {
		this.configuration = configuration;
	}
	
	public String sendSMS(String phone, String code) throws IOException, TemplateException {
		Twilio.init(sid, token);
		String smsContent = getSMSContent(code, TEMPLATE);
		Message message = Message.creator(
				new PhoneNumber(phone),
		        new PhoneNumber(this.phone), 
		        smsContent)
			.create();
		return message.getStatus().toString();
	}
	
	private String getSMSContent(String code, String template) throws IOException, TemplateException {
		StringWriter writer = new StringWriter();
		Map<String, Object> model = new HashMap<>();
		model.put("code", code);
		configuration.getTemplate(template).process(model, writer);
		return writer.getBuffer().toString();
	}

	/**
	 * Send normal SMS 
	 * @param phone phone number
	 * @param smsContent content 
	 * @return
	 * @throws IOException
	 * @throws TemplateException
	 */
	public String sendNormalSMS(String phone, String smsContent) throws IOException {
		Twilio.init(sid, token);
		Message message = Message.creator(
				new PhoneNumber(phone),
		        new PhoneNumber(this.phone), 
		        smsContent)
			.create();
		return message.getStatus().toString();
	}

}
