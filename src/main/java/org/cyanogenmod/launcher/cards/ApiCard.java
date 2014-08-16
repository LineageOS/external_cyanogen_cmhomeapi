package org.cyanogenmod.launcher.cards;

import it.gmariotti.cardslib.library.internal.Card;
import it.gmariotti.cardslib.library.internal.Card.OnUndoSwipeListListener;

import org.cyanogenmod.launcher.cardprovider.CmHomeApiCardProvider;
import org.cyanogenmod.launcher.home.api.cards.CardData;

import android.content.Context;

public class ApiCard extends Card implements OnUndoSwipeListListener {

    private CardData mCardData;

    public ApiCard(Context context, CardData cardData) {
        super(context);
        init(cardData);
    }

    public ApiCard(Context context, int innerLayout, CardData cardData) {
        super(context, innerLayout);
        init(cardData);
    }

    private void init(CardData cardData) {
        mCardData = cardData;
        setSwipeable(true);
        setOnUndoSwipeListListener(this);
        if (cardData != null) {
            setId(cardData.getGlobalId());
        }
    }

    public void setApiAuthority(String authority) {
        mCardData.setAuthority(authority);
    }

    public String getApiAuthority() {
        return mCardData.getAuthority();
    }

    public long getDbId() {
        return mCardData.getId();
    }

    public void updateFromCardData(CardData cardData) {
        mCardData = cardData;
        if (cardData != null) {
            setId(cardData.getGlobalId());
        }
    }

    public CardData getCardData() {
        return mCardData;
    }

    @Override
    public void onUndoSwipe(Card card, boolean timedOut) {
        if (mCardData != null && timedOut) {
            boolean deleted = mCardData.unpublish(mContext);
            if (deleted) {
                CmHomeApiCardProvider.sendCardDeletedBroadcast(mContext, mCardData);
            }
        }
    }
}