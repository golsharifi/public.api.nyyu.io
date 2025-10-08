package com.ndb.auction.dao.oracle.transactions;

import java.util.List;

import com.ndb.auction.models.transactions.Transaction;

public interface ITransactionDao {
    public Transaction insert(Transaction m);
    public List<? extends Transaction> selectAll(String orderBy); 
    public List<? extends Transaction> selectByUser(int userId, String orderBy);
    public Transaction selectById(int id);
    public int update(int id, int status);
}
