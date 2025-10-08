package com.ndb.auction.dao.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import com.ndb.auction.models.TokenAsset;

import org.springframework.stereotype.Repository;

@Repository
public class TokenAssetDao extends BaseOracleDao {

	private static final String TABLE_NAME = "NDB.TBL_TOKEN_ASSET";

	private static TokenAsset extract(ResultSet rs) throws SQLException {
		TokenAsset model = new TokenAsset();
		model.setId(rs.getInt("ID"));
		model.setTokenName(rs.getString("TOKEN_NAME"));
		model.setTokenSymbol(rs.getString("TOKEN_SYMBOL"));
		model.setNetwork(rs.getString("NETWORK"));
		model.setAddress(rs.getString("ADDRESS"));
		model.setSymbol(rs.getString("SYMBOL"));
		return model;
	}

	public TokenAssetDao() {
		super(TABLE_NAME);
	}

	public TokenAsset selectById(int id) {
		String sql = "SELECT * FROM NDB.TBL_TOKEN_ASSET WHERE ID=? AND DELETED=0";
		return jdbcTemplate.query(sql, rs -> {
			if (!rs.next())
				return null;
			return extract(rs);
		}, id);
	}

	public List<TokenAsset> selectAll(String orderby) {
		String sql = "SELECT * FROM NDB.TBL_TOKEN_ASSET";
		if (orderby == null)
			orderby = "ID";
		sql += " ORDER BY " + orderby;
		return jdbcTemplate.query(sql, (rs, rownumber) -> extract(rs));
	}

	public int insert(TokenAsset m) {
		String sql = "INSERT INTO TBL_TOKEN_ASSET(ID,TOKEN_NAME,TOKEN_SYMBOL,NETWORK,ADDRESS,SYMBOL)"
				+ "VALUES(SEQ_TOKEN_ASSET.NEXTVAL,?,?,?,?,?)";
		return jdbcTemplate.update(sql, m.getTokenName(), m.getTokenSymbol(), m.getNetwork(), m.getAddress(),
				m.getSymbol());
	}

	public int updateSymbol(int id, String symbol) {
		String sql = "UPDATE TBL_TOKEN_ASSET SET SYMBOL = ? WHERE ID = ?";
		return jdbcTemplate.update(sql, symbol, id);
	}

	public int updateDeleted(int id) {
		String sql = "DELETE FROM TBL_TOKEN_ASSET WHERE ID=?";
		return jdbcTemplate.update(sql, id);
	}

}
