package com.ndb.auction.dao.oracle.verify;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import com.ndb.auction.dao.oracle.BaseOracleDao;
import com.ndb.auction.dao.oracle.Table;
import com.ndb.auction.models.CountrySetting;

import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import lombok.NoArgsConstructor;

@Repository
@NoArgsConstructor
@Table(name = "TBL_COUNTRY_SETTINGS")
public class CountrySettingDao extends BaseOracleDao {

    private static CountrySetting extract(ResultSet rs) throws SQLException {
        CountrySetting m = new CountrySetting();
        m.setId(rs.getInt("ID"));
        m.setCountryCode(rs.getString("COUNTRY_CODE"));
        m.setVerificationExempt(rs.getBoolean("VERIFICATION_EXEMPT"));
        m.setBlocked(rs.getBoolean("BLOCKED"));
        m.setBlockReason(rs.getString("BLOCK_REASON"));
        m.setNotes(rs.getString("NOTES"));
        m.setCreatedBy(rs.getInt("CREATED_BY"));
        m.setUpdatedBy(rs.getInt("UPDATED_BY"));
        m.setCreateDate(rs.getTimestamp("CREATE_DATE").getTime());
        m.setUpdateDate(rs.getTimestamp("UPDATE_DATE").getTime());
        return m;
    }

    public List<CountrySetting> getAllCountrySettings() {
        String sql = "SELECT * FROM TBL_COUNTRY_SETTINGS ORDER BY COUNTRY_CODE";
        return jdbcTemplate.query(sql, new RowMapper<CountrySetting>() {
            @Override
            public CountrySetting mapRow(ResultSet rs, int rowNum) throws SQLException {
                return extract(rs);
            }
        });
    }

    public CountrySetting getCountrySettingByCode(String countryCode) {
        String sql = "SELECT * FROM TBL_COUNTRY_SETTINGS WHERE COUNTRY_CODE = ?";
        return jdbcTemplate.query(sql, new ResultSetExtractor<CountrySetting>() {
            @Override
            public CountrySetting extractData(ResultSet rs) throws SQLException {
                if (!rs.next())
                    return null;
                return extract(rs);
            }
        }, countryCode.toUpperCase());
    }

    public List<String> getBlockedCountries() {
        String sql = "SELECT COUNTRY_CODE FROM TBL_COUNTRY_SETTINGS WHERE BLOCKED = 1";
        return jdbcTemplate.queryForList(sql, String.class);
    }

    public List<String> getVerificationExemptCountries() {
        String sql = "SELECT COUNTRY_CODE FROM TBL_COUNTRY_SETTINGS WHERE VERIFICATION_EXEMPT = 1";
        return jdbcTemplate.queryForList(sql, String.class);
    }

    public int insertCountrySetting(CountrySetting setting) {
        String sql = """
                    INSERT INTO TBL_COUNTRY_SETTINGS
                    (ID, COUNTRY_CODE, VERIFICATION_EXEMPT, BLOCKED, BLOCK_REASON, NOTES, CREATED_BY, CREATE_DATE, UPDATE_DATE)
                    VALUES (SEQ_COUNTRY_SETTINGS.NEXTVAL, ?, ?, ?, ?, ?, ?, SYSDATE, SYSDATE)
                """;
        return jdbcTemplate.update(sql,
                setting.getCountryCode().toUpperCase(),
                setting.isVerificationExempt() ? 1 : 0,
                setting.isBlocked() ? 1 : 0,
                setting.getBlockReason(),
                setting.getNotes(),
                setting.getCreatedBy());
    }

    public int updateCountrySetting(CountrySetting setting) {
        String sql = """
                    UPDATE TBL_COUNTRY_SETTINGS SET
                        VERIFICATION_EXEMPT = ?,
                        BLOCKED = ?,
                        BLOCK_REASON = ?,
                        NOTES = ?,
                        UPDATED_BY = ?,
                        UPDATE_DATE = SYSDATE
                    WHERE COUNTRY_CODE = ?
                """;
        return jdbcTemplate.update(sql,
                setting.isVerificationExempt() ? 1 : 0,
                setting.isBlocked() ? 1 : 0,
                setting.getBlockReason(),
                setting.getNotes(),
                setting.getUpdatedBy(),
                setting.getCountryCode().toUpperCase());
    }

    public int insertOrUpdateCountrySetting(CountrySetting setting) {
        String sql = """
                    MERGE INTO TBL_COUNTRY_SETTINGS USING DUAL ON (COUNTRY_CODE = ?)
                    WHEN MATCHED THEN UPDATE SET
                        VERIFICATION_EXEMPT = ?,
                        BLOCKED = ?,
                        BLOCK_REASON = ?,
                        NOTES = ?,
                        UPDATED_BY = ?,
                        UPDATE_DATE = SYSDATE
                    WHEN NOT MATCHED THEN INSERT (
                        ID, COUNTRY_CODE, VERIFICATION_EXEMPT, BLOCKED, BLOCK_REASON, NOTES, CREATED_BY, CREATE_DATE, UPDATE_DATE
                    ) VALUES (
                        SEQ_COUNTRY_SETTINGS.NEXTVAL, ?, ?, ?, ?, ?, ?, SYSDATE, SYSDATE
                    )
                """;
        return jdbcTemplate.update(sql,
                setting.getCountryCode().toUpperCase(),
                setting.isVerificationExempt() ? 1 : 0,
                setting.isBlocked() ? 1 : 0,
                setting.getBlockReason(),
                setting.getNotes(),
                setting.getUpdatedBy(),
                setting.getCountryCode().toUpperCase(),
                setting.isVerificationExempt() ? 1 : 0,
                setting.isBlocked() ? 1 : 0,
                setting.getBlockReason(),
                setting.getNotes(),
                setting.getCreatedBy());
    }

    public int deleteCountrySetting(String countryCode) {
        String sql = "DELETE FROM TBL_COUNTRY_SETTINGS WHERE COUNTRY_CODE = ?";
        return jdbcTemplate.update(sql, countryCode.toUpperCase());
    }

    /**
     * Get all countries with their user counts and verification status
     */
    public List<java.util.Map<String, Object>> getCountriesWithUserCount() {
        String sql = """
                    SELECT
                        u.COUNTRY as country_code,
                        COUNT(*) as user_count,
                        COALESCE(cs.VERIFICATION_EXEMPT, 0) as verification_exempt,
                        COALESCE(cs.BLOCKED, 0) as blocked,
                        cs.BLOCK_REASON,
                        cs.NOTES
                    FROM TBL_USER u
                    LEFT JOIN TBL_COUNTRY_SETTINGS cs ON u.COUNTRY = cs.COUNTRY_CODE
                    WHERE u.DELETED = 0
                    GROUP BY u.COUNTRY, cs.VERIFICATION_EXEMPT, cs.BLOCKED, cs.BLOCK_REASON, cs.NOTES

                    UNION ALL

                    SELECT
                        cs.COUNTRY_CODE as country_code,
                        0 as user_count,
                        cs.VERIFICATION_EXEMPT,
                        cs.BLOCKED,
                        cs.BLOCK_REASON,
                        cs.NOTES
                    FROM TBL_COUNTRY_SETTINGS cs
                    WHERE cs.COUNTRY_CODE NOT IN (
                        SELECT DISTINCT COUNTRY FROM TBL_USER WHERE DELETED = 0
                    )

                    ORDER BY country_code
                """;
        return jdbcTemplate.queryForList(sql);
    }
}