package com.ndb.auction.web3;

import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.UUID;

import com.ndb.auction.contracts.NdbWallet;
import com.ndb.auction.config.Web3jConfig;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.contracts.eip20.generated.ERC20;
import org.web3j.crypto.exception.CipherException;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Wallet;
import org.web3j.crypto.WalletFile;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

@Service
public class NdbWalletService {

    @Autowired
    private Web3jConfig web3Config;

    @Autowired
    private Web3j web3j; // Main Web3j instance

    @Autowired
    @Qualifier("localWeb3j")
    private Web3j localNet; // Local network Web3j instance

    @Value("${ndb.private.key}")
    private String ndbPrivateKey;

    @Value("${nyyu.wallet.password}")
    private String nyyuWalletPassword;

    // Gas configuration from properties
    private final BigInteger gasPrice;
    private final BigInteger gasLimit;

    private NdbWallet ndbWallet;

    @Autowired
    public NdbWalletService(Web3jConfig web3Config) {
        this.web3Config = web3Config;

        // Initialize gas settings from config properties
        this.gasPrice = new BigInteger(web3Config.getGasPrice());
        this.gasLimit = new BigInteger(web3Config.getGasLimit());

        // Initialize wallet - will be set after dependency injection
        this.ndbWallet = null;
    }

    // Post-construct initialization (after all dependencies are injected)
    @jakarta.annotation.PostConstruct
    public void init() {
        this.ndbWallet = loadTraderContract(web3Config.getAdminWalletPassword());
    }

    @SuppressWarnings("deprecation")
    private NdbWallet loadTraderContract(String _password) {
        NdbWallet ndbWallet = null;
        try {
            Credentials credentials = Credentials.create(_password);
            ndbWallet = NdbWallet.load(
                    web3Config.getContract().getNdbWallet(),
                    web3j,
                    credentials,
                    gasPrice,
                    gasLimit);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ndbWallet;
    }

    /// Create new account!
    public TransactionReceipt createAccount(String id, String email) {
        TransactionReceipt receipt = null;
        try {
            if (ndbWallet != null) {
                System.out.println("Adding User...");
                receipt = ndbWallet.createAccount(id, email).send();
                System.out.println("Successfully Added New User: " + email);
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return receipt;
    }

    public TransactionReceipt createWalletWithEmail(String email, String tokenType, String privateKey) {
        TransactionReceipt receipt = null;
        try {
            if (ndbWallet != null) {
                System.out.println("Creating new wallet...");
                receipt = ndbWallet.createWalletWithEmail(email, tokenType, privateKey).send();
                System.out.println("New wallet is created: " + tokenType);
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return receipt;
    }

    public TransactionReceipt createWalletWithId(String id, String tokenType, String privateKey) {
        TransactionReceipt receipt = null;
        try {
            if (ndbWallet != null) {
                System.out.println("Creating new wallet...");
                receipt = ndbWallet.createWalletWithId(id, tokenType, privateKey).send();
                System.out.println("New wallet is created: " + tokenType);
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return receipt;
    }

    public BigInteger getDecimals() {
        BigInteger decimal = null;
        try {
            if (ndbWallet != null) {
                decimal = ndbWallet.decimals().send();
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return decimal;
    }

    public TransactionReceipt increaseHoldBalanceWithEmail(String email, String tokenType, BigInteger amount) {
        TransactionReceipt receipt = null;
        try {
            if (ndbWallet != null) {
                receipt = ndbWallet.increaseHoldBalanceWithEmail(email, tokenType, amount).send();
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return receipt;
    }

    public TransactionReceipt decreaseHoldBalanceWithId(String id, String tokenType, BigInteger amount) {
        TransactionReceipt receipt = null;
        try {
            if (ndbWallet != null) {
                receipt = ndbWallet.decreaseHoldBalanceWithId(id, tokenType, amount).send();
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return receipt;
    }

    public BigInteger getHoldBalanceWithEmail(String email, String tokenType) {
        BigInteger holdBalance = null;
        try {
            if (ndbWallet != null) {
                holdBalance = ndbWallet.getHoldBalanceWithEmail(email, tokenType).send();
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return holdBalance;
    }

    public BigInteger getHoldBalanceWithId(String id, String tokenType) {
        BigInteger holdBalance = null;
        try {
            if (ndbWallet != null) {
                holdBalance = ndbWallet.getHoldBalanceWithId(id, tokenType).send();
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return holdBalance;
    }

    public String getOwner() {
        String owner = null;
        try {
            if (ndbWallet != null) {
                owner = ndbWallet.getOwner().send();
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return owner;
    }

    public String getPrivateKeyWithEmail(String email, String tokenType) {
        String privateKey = null;
        try {
            if (ndbWallet != null) {
                privateKey = ndbWallet.getPrivateKeyWithEmail(email, tokenType).send();
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return privateKey;
    }

    public String getPrivateKeyWithId(String id, String tokenType) {
        String privateKey = null;
        try {
            if (ndbWallet != null) {
                privateKey = ndbWallet.getPrivateKeyWithId(id, tokenType).send();
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return privateKey;
    }

    public TransactionReceipt decreaseHoldBalanceWithEmail(String email, String tokenType, BigInteger amount) {
        TransactionReceipt receipt = null;
        try {
            if (ndbWallet != null) {
                receipt = ndbWallet.decreaseHoldBalanceWithEmail(email, tokenType, amount).send();
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return receipt;
    }

    public TransactionReceipt increaseHoldBalanceWithId(String id, String tokenType, BigInteger amount) {
        TransactionReceipt receipt = null;
        try {
            if (ndbWallet != null) {
                receipt = ndbWallet.increaseHoldBalanceWithId(id, tokenType, amount).send();
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return receipt;
    }

    public TransactionReceipt setHoldBalanceWithEmail(String email, String tokenType, BigInteger amount) {
        TransactionReceipt receipt = null;
        try {
            if (ndbWallet != null) {
                receipt = ndbWallet.setHoldBalanceWithEmail(email, tokenType, amount).send();
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return receipt;
    }

    public TransactionReceipt setHoldBalanceWithId(String id, String tokenType, BigInteger amount) {
        TransactionReceipt receipt = null;
        try {
            if (ndbWallet != null) {
                receipt = ndbWallet.setHoldBalanceWithId(id, tokenType, amount).send();
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return receipt;
    }

    public TransactionReceipt transferOwnerShip(String newOwner) {
        TransactionReceipt receipt = null;
        try {
            if (ndbWallet != null) {
                receipt = ndbWallet.transferOwnership(newOwner).send();
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return receipt;
    }

    // Generate wallet address!
    public String generateWalletAddress(String id, String tType) {
        String seed = UUID.randomUUID().toString();
        String address = null;

        try {
            ECKeyPair ecKeyPair = Keys.createEcKeyPair();
            BigInteger privateKeyInDec = ecKeyPair.getPrivateKey();
            String sPrivatekeyInHex = privateKeyInDec.toString(16);
            WalletFile wallet = Wallet.createLight(seed, ecKeyPair);
            address = wallet.getAddress();

            System.out.println("Generated private key: " + sPrivatekeyInHex);
            System.out.println("Generated address: 0x" + address);

            // save to database
            // createWalletWithId(id, tokenType, privateKey);

        } catch (InvalidAlgorithmParameterException e) {
            System.err.println("Invalid algorithm parameter: " + e.getMessage());
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Algorithm not found: " + e.getMessage());
            e.printStackTrace();
        } catch (NoSuchProviderException e) {
            System.err.println("Provider not found: " + e.getMessage());
            e.printStackTrace();
        } catch (CipherException e) {
            System.err.println("Cipher exception occurred: " + e.getMessage());
            e.printStackTrace();
        }

        return address != null ? "0x" + address : null;
    }

    @SuppressWarnings("deprecation")
    public BigInteger getWalletBalance(String token, String network, String address) {
        try {
            // Use the existing NDB private key from properties
            Credentials credentials = Credentials.create(ndbPrivateKey);

            ERC20 usdtToken = ERC20.load(
                    web3Config.getContract().getLocalToken(),
                    localNet,
                    credentials,
                    gasPrice,
                    gasLimit);

            return usdtToken.balanceOf(address).send();

        } catch (Exception e) {
            e.printStackTrace();
            return BigInteger.valueOf(-1);
        }
    }

    public Boolean withdrawFunds(String token, String network, String userId, int amount) {
        // TODO: Implement withdrawal logic
        // get private key from database/config based on userId
        // get token object based on token parameter
        // check network and use appropriate Web3j instance
        // perform withdrawal transaction

        System.out.println("Withdrawal requested for user: " + userId + ", amount: " + amount + " " + token);
        return true;
    }

    public Boolean transferFunds(String token, String network, String address, long amount) {
        try {
            // Use configured demo key instead of hardcoded
            Credentials credentials = Credentials.create(web3Config.getWallet().getDemoPrivateKey1());

            @SuppressWarnings("deprecation")
            ERC20 erc20 = ERC20.load(
                    web3Config.getContract().getLocalToken(),
                    localNet,
                    credentials,
                    gasPrice,
                    gasLimit);

            BigInteger _amount = BigInteger.valueOf(amount * 100);
            TransactionReceipt receipt = erc20.transfer(address, _amount).send();

            System.out.println("Transfer successful. Transaction hash: " + receipt.getTransactionHash());
            return true;

        } catch (Exception e) {
            System.err.println("Transfer failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public Boolean transferFromFunds(String token, String network, String from, String to, int amount) {
        try {
            // Use configured demo key instead of hardcoded
            Credentials credentials = Credentials.create(web3Config.getWallet().getDemoPrivateKey2());

            @SuppressWarnings("deprecation")
            ERC20 erc20 = ERC20.load(
                    web3Config.getContract().getLocalToken(),
                    localNet,
                    credentials,
                    gasPrice,
                    gasLimit);

            BigInteger _amount = BigInteger.valueOf(amount * 100);
            TransactionReceipt receipt = erc20.transferFrom(from, to, _amount).send();

            System.out.println("TransferFrom successful. Transaction hash: " + receipt.getTransactionHash());
            return true;

        } catch (Exception e) {
            System.err.println("TransferFrom failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public Boolean makeAllowance(String token, String network, String address, int amount) {
        try {
            // Use configured demo key instead of hardcoded
            Credentials credentials = Credentials.create(web3Config.getWallet().getDemoPrivateKey1());

            @SuppressWarnings("deprecation")
            ERC20 erc20 = ERC20.load(
                    web3Config.getContract().getLocalToken(),
                    localNet,
                    credentials,
                    gasPrice,
                    gasLimit);

            BigInteger _amount = BigInteger.valueOf(amount * 100);
            TransactionReceipt receipt = erc20.approve(address, _amount).send();

            System.out.println("Approval successful. Transaction hash: " + receipt.getTransactionHash());
            return true;

        } catch (Exception e) {
            System.err.println("Approval failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public String getAllowance(String token, String network, String owner, String spender) {
        try {
            // Use configured demo key instead of hardcoded
            Credentials credentials = Credentials.create(web3Config.getWallet().getDemoPrivateKey2());

            @SuppressWarnings("deprecation")
            ERC20 usdtToken = ERC20.load(
                    web3Config.getContract().getLocalToken(),
                    localNet,
                    credentials,
                    gasPrice,
                    gasLimit);

            BigInteger allowance = usdtToken.allowance(owner, spender).send();
            return allowance.toString();

        } catch (Exception e) {
            System.err.println("Getting allowance failed: " + e.getMessage());
            e.printStackTrace();
            return "0";
        }
    }

    // Utility methods for getting configuration values
    public BigInteger getGasPrice() {
        return gasPrice;
    }

    public BigInteger getGasLimit() {
        return gasLimit;
    }

    public String getContractAddress() {
        return web3Config.getContract().getNdbWallet();
    }

    public String getLocalTokenAddress() {
        return web3Config.getContract().getLocalToken();
    }

    // Method to get Web3j instance for different networks
    public Web3j getWeb3jForNetwork(String network) {
        switch (network.toLowerCase()) {
            case "local":
                return localNet;
            case "bsc":
            case "mainnet":
                return web3j;
            default:
                return web3j;
        }
    }
}