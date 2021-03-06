package org.cyanogenmod.launcher.home.api;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.UriMatcher;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.util.LongSparseArray;
import org.cyanogenmod.launcher.cardprovider.ApiCardPackageChangedReceiver;
import org.cyanogenmod.launcher.cardprovider.CmHomeApiCardProvider;
import org.cyanogenmod.launcher.home.api.cards.CardData;
import org.cyanogenmod.launcher.home.api.cards.CardDataImage;
import org.cyanogenmod.launcher.home.api.provider.CmHomeContract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class CMHomeApiManager {
    private final static String TAG = "CMHomeApiManager";
    private final static String FEED_HOST_PERM
                                                            = "org.cyanogenmod.launcher.home.api.FEED_HOST";
    private final static String FEED_PUBLISH_PERM           =
            "org.cyanogenmod.launcher.home.api.FEED_PUBLISH";
    private final static String PROVIDER_AUTHORITY_SUFFIX   = ".cmhomeapi";
    private static final int    CARD_DATA_LIST              = 1;
    private static final int    CARD_DATA_ITEM              = 2;
    private static final int    CARD_DATA_DELETE_ITEM       = 3;
    private static final int    CARD_DATA_IMAGE_LIST        = 4;
    private static final int    CARD_DATA_IMAGE_ITEM        = 5;
    private static final int    CARD_DATA_IMAGE_DELETE_ITEM = 6;

    // All provider authorities that contain Cards.
    private List<String> mProviders = new ArrayList<String>();
    // Provider authority string -> SparseArray from card ID -> CardData
    private HashMap<String, LongSparseArray<CardData>> mCards = new HashMap<String,
            LongSparseArray<CardData>>();
    // Provider authority string -> SparseArray from card ID -> CardData
    // Stores cards that must be updated when it is time to display them
    private HashMap<String, LongSparseArray<CardData>> mCardUpdates = new HashMap<String,
                                                                    LongSparseArray<CardData>>();
    private HashMap<String, CardData> mImageIdsToCards = new HashMap<String, CardData>();
    private ArrayList<CardDataImage> mPendingImageUpdates = new ArrayList<CardDataImage>();
    private HashSet<String> mPendingImageRemovalIds = new HashSet<String>();
    private HashSet<String> mCardInsertsHandled = new HashSet<String>();

    private CardContentObserver           mContentObserver;
    private Handler                       mBackgroundThreadHandler;
    private ICMHomeApiUpdateListener      mApiUpdateListener;
    private ApiCardPackageChangedReceiver mPackageChangedReceiver;

    private Context mContext;

    private Runnable mLoadAllExtensionsRunnable = new Runnable() {
        @Override
        public void run() {
            loadAllExtensions();
            trackAllExtensions();
            loadAllCards();
            // Send a refresh broadcast for all packages after beginning to track them.
            sendRefreshBroadcastToAllTrackedPackages();
        }
    };

    public CMHomeApiManager(Context context, Handler backgroundThreadHandler) {
        mContext = context;
        mBackgroundThreadHandler = backgroundThreadHandler;
        init();
    }

    public boolean hasCard(String apiAuthority, long cardId) {
        LongSparseArray<CardData> cards = mCards.get(apiAuthority);
        boolean hasCard = false;
        if (cards != null) {
            hasCard = cards.get(cardId) != null;
        }
        return hasCard;
    }

    public CardData getCard(String apiAuthority, long cardId) {
        LongSparseArray<CardData> cards = mCards.get(apiAuthority);
        CardData card = null;
        if (cards != null) {
            card = cards.get(cardId);
        }
        return card;
    }

    public CardData getCardWithGlobalId(String cardId) {
        String[] idParts = cardId.split("/");
        if (idParts.length > 1) {
            String apiAuthority = idParts[0];
            String idString = idParts[1];
            try {
                long id = Long.parseLong(idString);
                return getCard(apiAuthority, id);
            } catch (NumberFormatException e) {
                // This card is either malformed or not a
                // CmHome API card. Return null.
            }
        }

        return null;
    }

    public void setApiUpdateListener(ICMHomeApiUpdateListener listener) {
        mApiUpdateListener = listener;
    }

    public void init() {
        // Register the package changed broadcast receiver
        mPackageChangedReceiver = new ApiCardPackageChangedReceiver(this);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        intentFilter.addDataScheme("package");
        mContext.registerReceiver(mPackageChangedReceiver, intentFilter);

        mBackgroundThreadHandler.post(mLoadAllExtensionsRunnable);
    }

    public void destroy() {
        if (mContentObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mContentObserver);
        }

        // After unregistering, clear the reference to mPackageChangedReceiver
        // so that it cannot be attempted to be unregistered twice in abnormal circumstances.
        if (mPackageChangedReceiver != null) {
            mContext.unregisterReceiver(mPackageChangedReceiver);
            mPackageChangedReceiver = null;
        }
    }

    private void loadAllExtensions() {
        PackageManager pm = mContext.getPackageManager();
        List<PackageInfo> providerPackages =
                pm.getInstalledPackages(PackageManager.GET_PROVIDERS);
        for (PackageInfo packageInfo : providerPackages) {
            loadExtensionIfSupported(packageInfo);
        }
    }

    private String loadExtensionIfSupported(PackageInfo packageInfo) {
        if (packageInfo != null) {
            ProviderInfo[] providers = packageInfo.providers;
            if (providers != null) {
                for (ProviderInfo providerInfo : providers) {
                    if (FEED_HOST_PERM.equals(providerInfo.readPermission)
                        && FEED_HOST_PERM.equals(providerInfo.writePermission)) {
                        mProviders.add(providerInfo.authority);
                        return providerInfo.authority;
                    }
                }
            }
        }
        return null;
    }

    private void loadExtensionAndCardsForPackageIfSupported(String packageName,
                                                            boolean notifyListener) {
        PackageManager pm = mContext.getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_PROVIDERS);
            boolean authorized = pm.checkPermission(FEED_PUBLISH_PERM, packageName)
                                 == PackageManager.PERMISSION_GRANTED;
            if (authorized) {
                String authority = loadExtensionIfSupported(packageInfo);

                boolean alreadyExists = mProviders.contains(authority) &&
                                        mCards.containsKey(authority);

                // If the provider is already being tracked, our work is done
                if (authority != null && !alreadyExists) {
                    trackExtension(authority);
                    loadCards(authority, notifyListener);
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Unable to load providers for package: " + packageName);
        }
    }

    private void removeAllCardsForPackage(String packageName) {
        String providerAuthority = packageName + CmHomeApiCardProvider.CARD_AUTHORITY_APPEND_STRING;
        LongSparseArray<CardData> cards = mCards.get(providerAuthority);
        if (cards != null) {
            // Notify listener that all cards will be deleted
            for (int i = 0; i < cards.size(); i++) {
                CardData cardData = cards.valueAt(i);

                mApiUpdateListener.onCardDelete(cardData.getGlobalId());
                mCardInsertsHandled.remove(cardData.getGlobalId());
            }

            // Clear storage of all cards for this provider
            mProviders.remove(providerAuthority);
            mCards.remove(providerAuthority);
        }
    }

    private void trackExtension(String authority) {
        if (mContentObserver == null) {
            mContentObserver = new CardContentObserver(mBackgroundThreadHandler);
        }

        ContentResolver contentResolver = mContext.getContentResolver();

        Uri getCardsUri = Uri.parse("content://" + authority + "/" +
                                         CmHomeContract.CardDataContract
                                                 .LIST_INSERT_UPDATE_URI_PATH);
        Uri getImagesUri = Uri.parse("content://" + authority + "/" +
                                     CmHomeContract.CardDataImageContract
                                             .LIST_INSERT_UPDATE_URI_PATH);
        contentResolver.registerContentObserver(getCardsUri,
                                                     true,
                                                     mContentObserver);
        contentResolver.registerContentObserver(getImagesUri,
                                                     true,
                                                     mContentObserver);
    }

    private void trackAllExtensions() {
        mContentObserver = new CardContentObserver(mBackgroundThreadHandler);

        for (String authority : mProviders) {
            trackExtension(authority);
        }
    }

    private void loadCards(String authority, boolean notifyListener) {
        Uri getCardsUri = Uri.parse("content://" + authority + "/" +
                                    CmHomeContract.CardDataContract
                                            .LIST_INSERT_UPDATE_URI_PATH);
        Uri getImagesUri = Uri.parse("content://" + authority + "/" +
                                     CmHomeContract.CardDataImageContract
                                             .LIST_INSERT_UPDATE_URI_PATH);
        List<CardData> cards = CardData.getAllPublishedCardDatas(mContext,
                                                                 getCardsUri,
                                                                 getImagesUri);

        //For quick access, build a LongSparseArray using the id as the key
        LongSparseArray<CardData> cardMap = mCards.get(authority);
        if (cardMap == null) {
            cardMap = new LongSparseArray<CardData>();
            mCards.put(authority, cardMap);
        }

        for (CardData card : cards) {
            cardMap.put(card.getId(), card);
            storeCardDataImagesForCardData(card);

            if (notifyListener) {
                notifyCardInsertOrUpdate(card.getGlobalId(), false);
            }
        }
    }

    private void storeCardDataImagesForCardData(CardData cardData) {
        for (CardDataImage image : cardData.getImages()) {
            mImageIdsToCards.put(image.getGlobalId(), cardData);
        }
    }

    private void loadAllCards() {
        for (String authority : mProviders) {
            loadCards(authority, false);
        }
    }

    private class CardContentObserver extends ContentObserver {

        public CardContentObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (!selfChange) {
                handleUriChange(uri);
            }
        }
    }

    private void handleUriChange(Uri uri) {
        String authority = uri.getAuthority();
        UriMatcher matcher = getUriMatcherForAuthority(authority);
        switch (matcher.match(uri)) {
            case CARD_DATA_LIST:
                // Todo: figure out what rows were changed?
                // It might not be trivial to handle a list of changes
                break;
            case CARD_DATA_ITEM:
                onCardInsertOrUpdate(uri);
                break;
            case CARD_DATA_DELETE_ITEM:
                onCardDelete(uri);
                break;
            case CARD_DATA_IMAGE_LIST:
                // Todo: figure out what rows were changed?
                // It might not be trivial to handle a list of changes
                break;
            case CARD_DATA_IMAGE_ITEM:
                onCardImageInsertOrUpdate(uri);
                break;
            case CARD_DATA_IMAGE_DELETE_ITEM:
                onCardImageDelete(uri);
                break;
            default:
                Log.w(TAG, "Unsupported Uri change notification: " + uri);
        }
    }

    private CardData retrieveCardDataFromProvider(Uri uri) {
        Cursor cursor = null;
        try {
            ContentResolver contentResolver = mContext.getContentResolver();
            cursor = contentResolver.query(uri,
                                            CmHomeContract.CardDataContract.PROJECTION_ALL,
                                            null,
                                            null,
                                            CmHomeContract.CardDataContract.DATE_CREATED_COL);
        // Catching all Exceptions, since we can't be sure what the extension will do.
        } catch (Exception e) {
            Log.e(TAG, "Error querying for CardDatas, ContentProvider threw an exception for uri:" +
                       " " + uri, e);
        }

        CardData theCard = null;
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                theCard = CardData.createFromCurrentCursorRow(cursor, uri.getAuthority());
            }
            cursor.close();
        }
        return theCard;
    }

    /**
     * Handle a new insert in the ContentProvider database that must be loaded into memory.
     * @param uri The URI of the ContentProvider insertion.
     */
    private void onCardInsertOrUpdate(Uri uri) {
        String authority = uri.getAuthority();
        LongSparseArray<CardData> cards = mCards.get(authority);

        CardData theNewCard = retrieveCardDataFromProvider(uri);
        if (theNewCard != null) {
            if (cards == null) {
                // First card of the provider, insertion occurred
                cards = new LongSparseArray<CardData>();
                storeCardDataImagesForCardData(theNewCard);
                cards.put(theNewCard.getId(), theNewCard);
                mCards.put(authority, cards);
                notifyCardInsertOrUpdate(theNewCard.getGlobalId(), false);
            } else {
                // Do we have an update or insertion?
                if (cards.get(theNewCard.getId()) != null &&
                    mCardInsertsHandled.contains(theNewCard.getGlobalId())) {
                    // update, queue it up
                    addCardUpdate(authority, theNewCard);
                } else {
                    // insertion, let it fly
                    cards.put(theNewCard.getId(), theNewCard);
                    storeCardDataImagesForCardData(theNewCard);
                    notifyCardInsertOrUpdate(theNewCard.getGlobalId(), false);
                }
            }
        }
    }

    /**
     * Adds a card to the updates that have been queued, so that they can be applied later.
     * @param authority The authority that the card belongs to.
     * @param cardData The cardData that will be updated.
     */
    private void addCardUpdate(String authority, CardData cardData) {
        LongSparseArray<CardData> cards = mCardUpdates.get(authority);
        if (cards == null) {
            cards = new LongSparseArray<CardData>();
            mCardUpdates.put(authority, cards);
        }
        cards.put(cardData.getId(), cardData);
    }

    /**
     * Remove a card update, if one exists for that cardData.
     * @param authority The authority that the card belongs to.
     * @param id The id of the cardData that will be removed.
     */
    private void removeCardUpdate(String authority, long id) {
        LongSparseArray<CardData> cards = mCardUpdates.get(authority);
        if (cards != null) {
            cards.remove(id);
        }
    }

    private void onCardDelete(Uri uri) {
        String authority = uri.getAuthority();
        long id = Long.parseLong(uri.getLastPathSegment());
        LongSparseArray<CardData> cards = mCards.get(authority);
        if (cards != null) {
            CardData cardData = cards.get(id);

            if (cardData != null) {
                for (CardDataImage image : cardData.getImages()) {
                    mImageIdsToCards.remove(image.getGlobalId());
                }

                String globalId = cardData.getGlobalId();
                cards.delete(id);

                mApiUpdateListener.onCardDelete(globalId);
                mCardInsertsHandled.remove(globalId);
            }
        }
        removeCardUpdate(authority, id);
    }

    private CardDataImage retrieveCardDataImageFromProvider(Uri uri) {
        Cursor cursor = null;
        try {
            ContentResolver contentResolver = mContext.getContentResolver();
            cursor = contentResolver.query(uri,
                                           CmHomeContract.CardDataImageContract.PROJECTION_ALL,
                                           null,
                                           null,
                                           null);
        // Catching all Exceptions, since we can't be sure what the extension will do.
        } catch (Exception e) {
            Log.e(TAG, "Error querying for CardDatas, ContentProvider threw an exception for uri:" +
                       " " + uri, e);
        }

        CardDataImage theImage = null;
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                // This will be called only for a single image insertion or update
                cursor.moveToFirst();
                theImage = CardDataImage.createFromCurrentCursorRow(cursor, uri.getAuthority());
            }
            cursor.close();
        }
        return theImage;
    }

    private void onCardImageInsertOrUpdate(Uri uri) {
        // Get CardDataImage from URI id
        CardDataImage newImage = retrieveCardDataImageFromProvider(uri);
        if (newImage != null) {
            CardData associatedCard = getCard(newImage.getAuthority(), newImage.getCardDataId());
            if (mCardInsertsHandled.contains(associatedCard.getGlobalId())) {
                mPendingImageUpdates.add(newImage);
                mPendingImageRemovalIds.remove(newImage.getGlobalId());
            } else {
                cardImageInsertOrUpdate(newImage, false);
            }
        }
    }

    private void cardImageInsertOrUpdate(CardDataImage newImage, boolean wasPending) {
        // Find the CardData it is associated with and update its images
        if (newImage != null) {
            CardData associatedCard = getCard(newImage.getAuthority(), newImage.getCardDataId());
            if (associatedCard != null) {
                associatedCard.addOrUpdateCardDataImage(newImage);
                mImageIdsToCards.put(newImage.getGlobalId(), associatedCard);
                notifyCardInsertOrUpdate(associatedCard.getGlobalId(), wasPending);
            }
        }
    }

    private void onCardImageDelete(Uri uri) {
        if (uri != null) {
            try {
                long id = Long.parseLong(uri.getLastPathSegment());
                String authority = uri.getAuthority();
                String cardDataImageGlobalId = authority + "/" + id;

                // Remove any pending updates to this image
                Iterator<CardDataImage> cardDataImageIterator = mPendingImageUpdates.iterator();
                while (cardDataImageIterator.hasNext()) {
                    if (cardDataImageGlobalId
                            .equals(cardDataImageIterator.next().getGlobalId())) {
                        cardDataImageIterator.remove();
                    }
                }

                // Store the image for pending deletion
                mPendingImageRemovalIds.add(cardDataImageGlobalId);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Unable to handle CardDataImage deletion for Uri: " + uri.toString());
            }
        }
    }

    private void removeCardDataImage(String cardDataImageGlobalId, boolean wasPending) {
        // Find the CardData it is associated with,
        // remove the image and notify about the update.
        CardData associatedCard = mImageIdsToCards.get(cardDataImageGlobalId);
        if (associatedCard != null) {
            associatedCard.removeCardDataImage(cardDataImageGlobalId);
            mImageIdsToCards.remove(cardDataImageGlobalId);
            notifyCardInsertOrUpdate(associatedCard.getGlobalId(), wasPending);
        }
    }

    public void processPendingUpdates() {
        for (Map.Entry<String, LongSparseArray<CardData>> entry : mCardUpdates.entrySet()) {
            LongSparseArray<CardData> cards = entry.getValue();
            for (int i = 0; i < cards.size(); i++) {
                updateCard(entry.getKey(), cards.valueAt(i), true);
            }
        }

        for (CardDataImage cardDataImage : mPendingImageUpdates) {
            cardImageInsertOrUpdate(cardDataImage, true);
        }

        for (String imageId : mPendingImageRemovalIds) {
            removeCardDataImage(imageId, true);
        }

        mPendingImageUpdates.clear();
        mPendingImageRemovalIds.clear();
    }

    private void updateCard(String authority, CardData theNewCard, boolean wasPending) {
        LongSparseArray<CardData> cards = mCards.get(authority);
        if (theNewCard != null) {
            if (cards == null) {
                // First card of the provider, insertion occurred
                cards = new LongSparseArray<CardData>();
                cards.put(theNewCard.getId(), theNewCard);
                mCards.put(authority, cards);

            } else {
                cards.put(theNewCard.getId(), theNewCard);
                storeCardDataImagesForCardData(theNewCard);
            }
            notifyCardInsertOrUpdate(theNewCard.getGlobalId(), wasPending);
        }
    }

    private void notifyCardInsertOrUpdate(String globalId, boolean wasPending) {
        boolean inserted = mApiUpdateListener.onCardInsertOrUpdate(globalId,
                                                                   wasPending);
        if (inserted) {
            mCardInsertsHandled.add(globalId);
        }
    }

    private UriMatcher getUriMatcherForAuthority(String authority) {
        UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);
        matcher.addURI(authority,
                       CmHomeContract.CardDataContract.LIST_INSERT_UPDATE_URI_PATH,
                       CARD_DATA_LIST);
        matcher.addURI(authority,
                       CmHomeContract.CardDataContract.SINGLE_ROW_INSERT_UPDATE_URI_PATH,
                       CARD_DATA_ITEM);
        matcher.addURI(authority,
                       CmHomeContract.CardDataContract.SINGLE_ROW_DELETE_URI_PATH_MATCH,
                       CARD_DATA_DELETE_ITEM);
        matcher.addURI(authority,
                       CmHomeContract.CardDataImageContract.LIST_INSERT_UPDATE_URI_PATH,
                       CARD_DATA_IMAGE_LIST);
        matcher.addURI(authority,
                       CmHomeContract.CardDataImageContract.SINGLE_ROW_INSERT_UPDATE_URI_PATH,
                       CARD_DATA_IMAGE_ITEM);
        matcher.addURI(authority,
                       CmHomeContract.CardDataImageContract.SINGLE_ROW_DELETE_URI_PATH_MATCH,
                       CARD_DATA_IMAGE_DELETE_ITEM);
        return matcher;
    }

    public List<CardData> getAllCardDatas() {
        List<CardData> theCards = new ArrayList<CardData>();
        for (LongSparseArray<CardData> cards : mCards.values()) {
            for (int i = 0; i < cards.size(); i++) {
                theCards.add(cards.valueAt(i));
            }
        }
        return theCards;
    }

    public void cardInsertedToUI(String globalId) {
        mCardInsertsHandled.add(globalId);
    }

    public interface ICMHomeApiUpdateListener {
        public boolean onCardInsertOrUpdate(String globalId, boolean wasPending);
        public void onCardDelete(String globalId);
    }

    /**
     * Loads and Adds all cards from the given package and registers to track for changes.
     */
    private class LoadPackageRunnable implements Runnable {
        private String mPackageName;
        private boolean mNotifyListeners = false;

        public LoadPackageRunnable(String packageName, boolean notifyListeners) {
            mPackageName = packageName;
            mNotifyListeners = notifyListeners;
        }

        @Override
        public void run() {
            loadExtensionAndCardsForPackageIfSupported(mPackageName, mNotifyListeners);
            sendRefreshBroadcast(mPackageName);
        }
    }

    private void sendRefreshBroadcastToAllTrackedPackages() {
        for (String provider : mProviders) {
            if (provider.contains(PROVIDER_AUTHORITY_SUFFIX)) {
                // All provider authorities must contain the package name
                // in the same format. Extract that here.
                String packageName =
                        provider.substring(0,
                                           provider.indexOf(PROVIDER_AUTHORITY_SUFFIX));
                sendRefreshBroadcast(packageName);
            }
        }
    }

    private void sendRefreshBroadcast(String packageName) {
        if (mContext != null) {
            Intent broadcast = new Intent();
            broadcast.setAction(CmHomeApiCardProvider
                                        .CM_HOME_API_REFRESH_REQUESTED_BROADCAST_ACTION);
            broadcast.setPackage(packageName);
            mContext.sendBroadcast(broadcast);
        }
    }

    /**
     * Removes all cards from the given package and notifies listeners of their
     * removal.
     */
    private class RemoveAllCardsForPackageRunnable implements Runnable {
        private String mPackageName;

        public RemoveAllCardsForPackageRunnable(String packageName) {
            mPackageName = packageName;
        }

        @Override
        public void run() {
            removeAllCardsForPackage(mPackageName);
        }
    }

    /**
     * Update the collection of cards contained in this CmHomeApiManager when
     * a package has been added, changed or removed.
     * @param action Should be one of {@link android.content.Intent#ACTION_PACKAGE_ADDED},
     *     {@link android.content.Intent#ACTION_PACKAGE_CHANGED}, or
     *     {@link android.content.Intent#ACTION_PACKAGE_REMOVED},
     *     ApiCardPackageChangedReceiver.PACKAGE_CHANGED_ENABLE_PROVIDER,
     *     ApiCardPackageChangedReceiver.PACKAGE_CHANGED_DISABLE_PROVIDER, or
     *     {@link android.content.Intent#ACTION_PACKAGE_DATA_CLEARED}.
     *     Any other action Strings will be ignored.
     * @param packageName The package name of the application that has been updated.
     */
    public void onPackageChanged(String action, String packageName) {
        if (Intent.ACTION_PACKAGE_ADDED.equals(action) ||
            ApiCardPackageChangedReceiver.PACKAGE_CHANGED_ENABLE_PROVIDER.equals(action)) {
            mBackgroundThreadHandler.post(new LoadPackageRunnable(packageName, true));
        } else if (ApiCardPackageChangedReceiver.PACKAGE_CHANGED_DISABLE_PROVIDER.equals(action) ||
                   Intent.ACTION_PACKAGE_REMOVED.equals(action) ||
                   Intent.ACTION_PACKAGE_DATA_CLEARED.equals(action)) {
            mBackgroundThreadHandler.post(new RemoveAllCardsForPackageRunnable(packageName));
        }
    }
}
