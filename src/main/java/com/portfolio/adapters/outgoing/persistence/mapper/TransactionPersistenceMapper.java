package com.portfolio.adapters.outgoing.persistence.mapper;

import com.portfolio.adapters.outgoing.persistence.entity.TransactionEntity;
import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "cdi")
public interface TransactionPersistenceMapper {

    @Mapping(target = "userId", source = "userId", qualifiedByName = "userIdToString")
    @Mapping(target = "transactionType", source = "transactionType", qualifiedByName = "toTypeDb")
    @Mapping(target = "assetType", source = "assetType", qualifiedByName = "toAssetDb")
    @Mapping(target = "currency", source = "currency", qualifiedByName = "toCurrencyDb")
    @Mapping(target = "commissionCurrency", source = "commissionCurrency", qualifiedByName = "toCurrencyDb")
    TransactionEntity toEntity(Transaction transaction);

    @Mapping(target = "userId", source = "userId", qualifiedByName = "stringToUserId")
    @Mapping(target = "transactionType", source = "transactionType", qualifiedByName = "fromTypeDb")
    @Mapping(target = "assetType", source = "assetType", qualifiedByName = "fromAssetDb")
    @Mapping(target = "currency", source = "currency", qualifiedByName = "fromCurrencyDb")
    @Mapping(target = "commissionCurrency", source = "commissionCurrency", qualifiedByName = "fromCurrencyDb")
    Transaction toDomain(TransactionEntity entity);

    @Named("userIdToString")
    default String userIdToString(UserId userId) {
        return userId != null ? userId.value() : null;
    }

    @Named("stringToUserId")
    default UserId stringToUserId(String value) {
        return value != null ? UserId.of(value) : null;
    }

    @Named("toTypeDb")
    default TransactionEntity.TransactionTypeDb toTypeDb(TransactionType type) {
        return type == null ? null : TransactionEntity.TransactionTypeDb.valueOf(type.name());
    }

    @Named("fromTypeDb")
    default TransactionType fromTypeDb(TransactionEntity.TransactionTypeDb type) {
        return type == null ? null : TransactionType.valueOf(type.name());
    }

    @Named("toAssetDb")
    default TransactionEntity.AssetTypeDb toAssetDb(AssetType type) {
        return type == null ? null : TransactionEntity.AssetTypeDb.valueOf(type.name());
    }

    @Named("fromAssetDb")
    default AssetType fromAssetDb(TransactionEntity.AssetTypeDb type) {
        return type == null ? null : AssetType.valueOf(type.name());
    }

    @Named("toCurrencyDb")
    default TransactionEntity.CurrencyDb toCurrencyDb(Currency currency) {
        return currency == null ? null : TransactionEntity.CurrencyDb.valueOf(currency.name());
    }

    @Named("fromCurrencyDb")
    default Currency fromCurrencyDb(TransactionEntity.CurrencyDb currency) {
        return currency == null ? null : Currency.valueOf(currency.name());
    }
}
