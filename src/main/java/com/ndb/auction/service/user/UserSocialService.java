package com.ndb.auction.service.user;

import com.ndb.auction.dao.oracle.user.UserSocialDao;
import com.ndb.auction.models.tier.Tier;
import com.ndb.auction.models.user.User;
import com.ndb.auction.models.user.UserSocial;
import com.ndb.auction.service.BaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserSocialService extends BaseService {
    @Autowired
    UserSocialDao socialDao;

    public String getTier(String discordUsername){
        UserSocial userSocial = socialDao.selectByDiscordUsername(discordUsername);
        User user = userDao.selectById(userSocial.getId());
        Tier userTier = tierDao.selectByLevel(user.getTierLevel());
        return userTier.getName();
    }

    public String getDiscordUsername(int userId){
        UserSocial userSocial = socialDao.selectById(userId);
        if (userSocial == null) return "";
        return userSocial.getDiscord();
    }

    public Boolean addDiscord(int userId,String discordUsername){
        try {
            socialDao.insertOrUpdate(UserSocial.builder().
                    id(userId).
                    discord(discordUsername).
                    build()
            );
            return true;
        } catch (Exception e){
            return false;
        }
    }
}
