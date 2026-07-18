package com.portfolio.adapters.incoming.rest;

import com.portfolio.core.model.UserId;
import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class UserContext {

    private UserId userId;

    public void setUserId(UserId userId) {
        this.userId = userId;
    }

    public UserId requireUserId() {
        if (userId == null) {
            throw new IllegalStateException("UserId not set on request context");
        }
        return userId;
    }
}
