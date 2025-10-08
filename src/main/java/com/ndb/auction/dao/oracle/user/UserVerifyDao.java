package com.ndb.auction.dao.oracle.user;

import java.sql.ResultSet;
import java.sql.SQLException;

import com.ndb.auction.dao.oracle.BaseOracleDao;
import com.ndb.auction.dao.oracle.Table;
import com.ndb.auction.models.user.UserVerify;

import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Repository
@NoArgsConstructor
@Table(name = "TBL_USER_VERIFY")
@Slf4j
public class UserVerifyDao extends BaseOracleDao {

	/**
	 * Helper method to properly convert Oracle NUMBER(1,0) to boolean
	 * Oracle stores booleans as NUMBER which JDBC returns as BigDecimal
	 */
	private static boolean convertToBoolean(ResultSet rs, String columnName) throws SQLException {
		Object value = rs.getObject(columnName);

		if (value == null) {
			return false;
		}

		// Handle Boolean type
		if (value instanceof Boolean) {
			return (Boolean) value;
		}

		// Handle Number types (BigDecimal, Integer, Long, etc.) - THIS IS THE CRITICAL
		// PART
		if (value instanceof Number) {
			int numValue = ((Number) value).intValue();
			return numValue != 0;
		}

		// Handle String type
		if (value instanceof String) {
			String str = ((String) value).trim();
			return str.equalsIgnoreCase("true") || str.equals("1") || str.equalsIgnoreCase("yes");
		}

		return false;
	}

	private static UserVerify extract(ResultSet rs) throws SQLException {
		UserVerify m = new UserVerify();
		m.setId(rs.getInt("ID"));

		// Use the helper method for all boolean fields to handle Oracle NUMBER
		// conversion
		m.setEmailVerified(convertToBoolean(rs, "EMAIL_VERIFIED"));
		m.setPhoneVerified(convertToBoolean(rs, "PHONE_VERIFIED"));
		m.setKycVerified(convertToBoolean(rs, "KYC_VERIFIED"));
		m.setAmlVerified(convertToBoolean(rs, "AML_VERIFIED"));
		m.setKybVerified(convertToBoolean(rs, "KYB_VERIFIED"));

		m.setRegDate(rs.getTimestamp("REG_DATE").getTime());
		m.setUpdateDate(rs.getTimestamp("UPDATE_DATE").getTime());

		return m;
	}

	public UserVerify selectById(int id) {
		String sql = "SELECT * FROM TBL_USER_VERIFY WHERE ID=?";
		return jdbcTemplate.query(sql, new ResultSetExtractor<UserVerify>() {
			@Override
			public UserVerify extractData(ResultSet rs) throws SQLException {
				if (!rs.next())
					return null;
				return extract(rs);
			}
		}, id);
	}

	public int insert(UserVerify m) {
		String sql = "INSERT INTO TBL_USER_VERIFY(ID,EMAIL_VERIFIED,PHONE_VERIFIED,KYC_VERIFIED,AML_VERIFIED,KYB_VERIFIED,REG_DATE,UPDATE_DATE)"
				+ "VALUES(?,?,?,?,?,?,SYSDATE,SYSDATE)";
		return jdbcTemplate.update(sql, m.getId(), m.isEmailVerified(), m.isPhoneVerified(), m.isKycVerified(),
				m.isAmlVerified(), m.isKybVerified());
	}

	public int insertOrUpdate(UserVerify m) {
		String sql = "MERGE INTO TBL_USER_VERIFY USING DUAL ON (ID=?)"
				+ "WHEN MATCHED THEN UPDATE SET EMAIL_VERIFIED=?,PHONE_VERIFIED=?,KYC_VERIFIED=?,AML_VERIFIED=?,KYB_VERIFIED=?,UPDATE_DATE=SYSDATE "
				+ "WHEN NOT MATCHED THEN INSERT(ID,EMAIL_VERIFIED,PHONE_VERIFIED,KYC_VERIFIED,AML_VERIFIED,KYB_VERIFIED,REG_DATE,UPDATE_DATE)"
				+ "VALUES(?,?,?,?,?,?,SYSDATE,SYSDATE)";
		return jdbcTemplate.update(sql, m.getId(), m.isEmailVerified(), m.isPhoneVerified(), m.isKycVerified(),
				m.isAmlVerified(), m.isKybVerified(), m.getId(), m.isEmailVerified(), m.isPhoneVerified(),
				m.isKycVerified(), m.isAmlVerified(), m.isKybVerified());
	}

	public int updateEmailVerified(int id, boolean value) {
		String sql = "UPDATE TBL_USER_VERIFY SET EMAIL_VERIFIED=?, UPDATE_DATE=SYSDATE WHERE ID=?";
		log.info("Updating EMAIL_VERIFIED for user {} to {}", id, value);
		return jdbcTemplate.update(sql, value ? 1 : 0, id);
	}

	public int updateKYCVerified(int id, boolean value) {
		String sql = "UPDATE TBL_USER_VERIFY SET KYC_VERIFIED=?, UPDATE_DATE=SYSDATE WHERE ID=?";
		log.info("🔄 Updating KYC_VERIFIED for user {} to {}", id, value);
		int result = jdbcTemplate.update(sql, value ? 1 : 0, id);
		log.info("✅ KYC_VERIFIED update result: {} rows affected", result);
		return result;
	}
}