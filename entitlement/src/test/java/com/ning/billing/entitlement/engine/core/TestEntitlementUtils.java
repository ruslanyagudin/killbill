/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.entitlement.engine.core;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.ning.billing.account.api.Account;
import com.ning.billing.api.TestApiListener.NextEvent;
import com.ning.billing.catalog.api.BillingActionPolicy;
import com.ning.billing.catalog.api.BillingPeriod;
import com.ning.billing.catalog.api.PlanPhaseSpecifier;
import com.ning.billing.catalog.api.PriceListSet;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.entitlement.EntitlementService;
import com.ning.billing.entitlement.EntitlementTestSuiteWithEmbeddedDB;
import com.ning.billing.entitlement.api.BlockingState;
import com.ning.billing.entitlement.api.BlockingStateType;
import com.ning.billing.entitlement.api.DefaultEntitlement;
import com.ning.billing.entitlement.api.DefaultEntitlementApi;
import com.ning.billing.entitlement.api.Entitlement.EntitlementActionPolicy;
import com.ning.billing.entitlement.api.EntitlementApiException;
import com.ning.billing.entitlement.dao.BlockingStateSqlDao;

public class TestEntitlementUtils extends EntitlementTestSuiteWithEmbeddedDB {

    private BlockingStateSqlDao sqlDao;
    private DefaultEntitlement baseEntitlement;
    private DefaultEntitlement addOnEntitlement;
    // Dates for the base plan only
    private DateTime baseEffectiveEOTCancellationOrChangeDateTime;
    private LocalDate baseEffectiveCancellationOrChangeDate;

    @BeforeMethod(groups = "slow")
    public void setUp() throws Exception {
        sqlDao = dbi.onDemand(BlockingStateSqlDao.class);

        final LocalDate initialDate = new LocalDate(2013, 8, 8);
        clock.setDay(initialDate);
        final Account account = accountApi.createAccount(getAccountData(7), callContext);

        testListener.pushExpectedEvents(NextEvent.CREATE, NextEvent.CREATE);

        // Create base entitlement
        final PlanPhaseSpecifier baseSpec = new PlanPhaseSpecifier("Shotgun", ProductCategory.BASE, BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, null);
        baseEntitlement = (DefaultEntitlement) entitlementApi.createBaseEntitlement(account.getId(), baseSpec, account.getExternalKey(), initialDate, callContext);

        // Add ADD_ON
        final PlanPhaseSpecifier addOnSpec = new PlanPhaseSpecifier("Telescopic-Scope", ProductCategory.ADD_ON, BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, null);
        addOnEntitlement = (DefaultEntitlement) entitlementApi.addEntitlement(baseEntitlement.getBundleId(), addOnSpec, initialDate, callContext);

        // Verify the initial state
        checkFutureBlockingStatesToCancel(baseEntitlement, null, null);
        checkFutureBlockingStatesToCancel(addOnEntitlement, null, null);
        checkFutureBlockingStatesToCancel(baseEntitlement, addOnEntitlement, null);

        testListener.pushExpectedEvents(NextEvent.PHASE, NextEvent.PHASE);
        // Phase for the base plan is 2013/09/07 (30 days trial) but it's 2013/09/08 for the add-on (1 month discount)
        clock.setDay(new LocalDate(2013, 9, 8));
        assertListenerStatus();

        // Note! Make sure to align CTD and cancellation/change effective time with the phase event effective time to avoid timing issues in comparisons
        baseEffectiveEOTCancellationOrChangeDateTime = baseEntitlement.getSubscriptionBase().getAllTransitions().get(1).getEffectiveTransitionTime().plusMonths(1);
        Assert.assertEquals(baseEffectiveEOTCancellationOrChangeDateTime.toLocalDate(), new LocalDate(2013, 10, 7));
        baseEffectiveCancellationOrChangeDate = baseEffectiveEOTCancellationOrChangeDateTime.toLocalDate();
        // Set manually since no invoice
        subscriptionInternalApi.setChargedThroughDate(baseEntitlement.getId(), baseEffectiveEOTCancellationOrChangeDateTime, internalCallContext);
    }

    @Test(groups = "slow", description = "Verify add-ons blocking states are added for EOT cancellations")
    public void testCancellationEOT() throws Exception {
        // Cancel the base plan
        final DefaultEntitlement cancelledBaseEntitlement = (DefaultEntitlement) baseEntitlement.cancelEntitlementWithPolicyOverrideBillingPolicy(EntitlementActionPolicy.END_OF_TERM, BillingActionPolicy.END_OF_TERM, callContext);
        // No blocking event (EOT)
        assertListenerStatus();

        // Verify we compute the right blocking states for the "read" path...
        checkFutureBlockingStatesToCancel(addOnEntitlement, null, null);
        checkFutureBlockingStatesToCancel(cancelledBaseEntitlement, addOnEntitlement, baseEffectiveEOTCancellationOrChangeDateTime);
        // and for the "write" path (which will be exercised when the future notification kicks in).
        // Note that no event are computed because the add-on is not cancelled yet
        checkActualBlockingStatesToCancel(cancelledBaseEntitlement, addOnEntitlement, null, false);
        // Verify also the blocking states DAO adds events not on disk
        checkBlockingStatesDAO(baseEntitlement, addOnEntitlement, baseEffectiveCancellationOrChangeDate, true);

        // Verify the notification kicks in
        testListener.pushExpectedEvents(NextEvent.CANCEL, NextEvent.BLOCK, NextEvent.CANCEL, NextEvent.BLOCK);
        clock.addDays(30);
        assertListenerStatus();

        // Refresh the state
        final DefaultEntitlement cancelledAddOnEntitlement = (DefaultEntitlement) entitlementApi.getEntitlementForId(addOnEntitlement.getId(), callContext);

        // Verify we compute the right blocking states for the "read" path...
        checkFutureBlockingStatesToCancel(cancelledBaseEntitlement, null, null);
        checkFutureBlockingStatesToCancel(cancelledAddOnEntitlement, null, null);
        checkFutureBlockingStatesToCancel(cancelledBaseEntitlement, cancelledAddOnEntitlement, null);
        // ...and for the "write" path (which has been exercised when the notification kicked in).
        checkActualBlockingStatesToCancel(cancelledBaseEntitlement, cancelledAddOnEntitlement, baseEffectiveEOTCancellationOrChangeDateTime, false);
        // Verify also the blocking states API doesn't add too many events (now on disk)
        checkBlockingStatesDAO(cancelledBaseEntitlement, cancelledAddOnEntitlement, baseEffectiveCancellationOrChangeDate, true);
    }

    @Test(groups = "slow", description = "Verify add-ons blocking states are not impacted for add-on IMM cancellations")
    public void testCancellationBaseEOTAddOnIMM() throws Exception {
        // Cancel the base plan
        final DefaultEntitlement cancelledBaseEntitlement = (DefaultEntitlement) baseEntitlement.cancelEntitlementWithPolicyOverrideBillingPolicy(EntitlementActionPolicy.END_OF_TERM, BillingActionPolicy.END_OF_TERM, callContext);
        // No blocking event (EOT)
        assertListenerStatus();

        // Cancel the add-on
        testListener.pushExpectedEvents(NextEvent.CANCEL, NextEvent.BLOCK);
        final DefaultEntitlement cancelledAddOnEntitlement = (DefaultEntitlement) addOnEntitlement.cancelEntitlementWithPolicyOverrideBillingPolicy(EntitlementActionPolicy.IMMEDIATE, BillingActionPolicy.IMMEDIATE, callContext);
        assertListenerStatus();

        // Verify the blocking states API doesn't mix the dates (all blocking states are on disk)
        checkBlockingStatesDAO(cancelledBaseEntitlement, cancelledAddOnEntitlement, baseEffectiveCancellationOrChangeDate, clock.getUTCToday(), true);
    }

    @Test(groups = "slow", description = "Verify add-ons blocking states are not impacted by IMM cancellations")
    public void testCancellationIMM() throws Exception {
        // Approximate check, as the blocking state check (checkBlockingStatesDAO) could be a bit off
        final DateTime cancellationDateTime = clock.getUTCNow();
        final LocalDate cancellationDate = clock.getUTCToday();

        // Cancel the base plan
        testListener.pushExpectedEvents(NextEvent.CANCEL, NextEvent.BLOCK, NextEvent.CANCEL, NextEvent.BLOCK);
        final DefaultEntitlement cancelledBaseEntitlement = (DefaultEntitlement) baseEntitlement.cancelEntitlementWithPolicyOverrideBillingPolicy(EntitlementActionPolicy.IMMEDIATE, BillingActionPolicy.IMMEDIATE, callContext);
        assertListenerStatus();

        // Refresh the add-on state
        final DefaultEntitlement cancelledAddOnEntitlement = (DefaultEntitlement) entitlementApi.getEntitlementForId(addOnEntitlement.getId(), callContext);

        // Verify we compute the right blocking states for the "read" path...
        checkFutureBlockingStatesToCancel(cancelledBaseEntitlement, null, null);
        checkFutureBlockingStatesToCancel(cancelledAddOnEntitlement, null, null);
        checkFutureBlockingStatesToCancel(cancelledBaseEntitlement, cancelledAddOnEntitlement, null);
        // ...and for the "write" path (which has been exercised in the cancel call above).
        checkActualBlockingStatesToCancel(cancelledBaseEntitlement, cancelledAddOnEntitlement, cancellationDateTime, true);
        // Verify also the blocking states DAO doesn't add too many events (all on disk)
        checkBlockingStatesDAO(cancelledBaseEntitlement, cancelledAddOnEntitlement, cancellationDate, true);

        clock.addDays(30);
        // No new event
        assertListenerStatus();

        checkFutureBlockingStatesToCancel(cancelledBaseEntitlement, null, null);
        checkFutureBlockingStatesToCancel(cancelledAddOnEntitlement, null, null);
        checkFutureBlockingStatesToCancel(cancelledBaseEntitlement, cancelledAddOnEntitlement, null);
        checkActualBlockingStatesToCancel(cancelledBaseEntitlement, cancelledAddOnEntitlement, cancellationDateTime, true);
        checkBlockingStatesDAO(cancelledBaseEntitlement, cancelledAddOnEntitlement, cancellationDate, true);
    }

    // See https://github.com/killbill/killbill/issues/121
    @Test(groups = "slow", description = "Verify add-ons blocking states are not impacted by EOT billing cancellations")
    public void testCancellationIMMBillingEOT() throws Exception {
        // Approximate check, as the blocking state check (checkBlockingStatesDAO) could be a bit off
        final DateTime cancellationDateTime = clock.getUTCNow();
        final LocalDate cancellationDate = clock.getUTCToday();

        // Cancel the base plan
        testListener.pushExpectedEvents(NextEvent.BLOCK, NextEvent.BLOCK);
        final DefaultEntitlement cancelledBaseEntitlement = (DefaultEntitlement) baseEntitlement.cancelEntitlementWithPolicyOverrideBillingPolicy(EntitlementActionPolicy.IMMEDIATE, BillingActionPolicy.END_OF_TERM, callContext);
        assertListenerStatus();

        // Refresh the add-on state
        final DefaultEntitlement cancelledAddOnEntitlement = (DefaultEntitlement) entitlementApi.getEntitlementForId(addOnEntitlement.getId(), callContext);

        // Verify we compute the right blocking states for the "read" path...
        checkFutureBlockingStatesToCancel(cancelledBaseEntitlement, null, null);
        checkFutureBlockingStatesToCancel(cancelledAddOnEntitlement, null, null);
        checkFutureBlockingStatesToCancel(cancelledBaseEntitlement, cancelledAddOnEntitlement, null);
        // ...and for the "write" path (which has been exercised in the cancel call above).
        checkActualBlockingStatesToCancel(cancelledBaseEntitlement, cancelledAddOnEntitlement, cancellationDateTime, true);
        // Verify also the blocking states DAO doesn't add too many events (all on disk)
        checkBlockingStatesDAO(cancelledBaseEntitlement, cancelledAddOnEntitlement, cancellationDate, true);

        testListener.pushExpectedEvents(NextEvent.CANCEL, NextEvent.CANCEL);
        clock.addDays(30);
        assertListenerStatus();

        checkFutureBlockingStatesToCancel(cancelledBaseEntitlement, null, null);
        checkFutureBlockingStatesToCancel(cancelledAddOnEntitlement, null, null);
        checkFutureBlockingStatesToCancel(cancelledBaseEntitlement, cancelledAddOnEntitlement, null);
        checkActualBlockingStatesToCancel(cancelledBaseEntitlement, cancelledAddOnEntitlement, cancellationDateTime, true);
        checkBlockingStatesDAO(cancelledBaseEntitlement, cancelledAddOnEntitlement, cancellationDate, true);
    }

    @Test(groups = "slow", description = "Verify add-ons blocking states are added for EOT change plans")
    public void testChangePlanEOT() throws Exception {
        // Change plan EOT to Assault-Rifle (Telescopic-Scope is included)
        final DefaultEntitlement changedBaseEntitlement = (DefaultEntitlement) baseEntitlement.changePlanWithDate("Assault-Rifle", BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, new LocalDate(2013, 10, 7), callContext);
        // No blocking event (EOT)
        assertListenerStatus();

        // Verify we compute the right blocking states for the "read" path...
        checkFutureBlockingStatesToCancel(addOnEntitlement, null, null);
        checkFutureBlockingStatesToCancel(changedBaseEntitlement, addOnEntitlement, baseEffectiveEOTCancellationOrChangeDateTime);
        // ...and for the "write" path (which will be exercised when the future notification kicks in).
        // Note that no event are computed because the add-on is not cancelled yet
        checkActualBlockingStatesToCancel(changedBaseEntitlement, addOnEntitlement, null, false);
        // Verify also the blocking states DAO adds events not on disk
        checkBlockingStatesDAO(changedBaseEntitlement, addOnEntitlement, baseEffectiveCancellationOrChangeDate, false);

        // Verify the notification kicks in
        testListener.pushExpectedEvents(NextEvent.CHANGE, NextEvent.CANCEL, NextEvent.BLOCK);
        clock.addDays(30);
        assertListenerStatus();

        // Refresh the state
        final DefaultEntitlement cancelledAddOnEntitlement = (DefaultEntitlement) entitlementApi.getEntitlementForId(addOnEntitlement.getId(), callContext);

        // Verify we compute the right blocking states for the "read" path...
        checkFutureBlockingStatesToCancel(changedBaseEntitlement, null, null);
        checkFutureBlockingStatesToCancel(cancelledAddOnEntitlement, null, null);
        checkFutureBlockingStatesToCancel(changedBaseEntitlement, cancelledAddOnEntitlement, null);
        // ...and for the "write" path (which has been exercised when the notification kicked in).
        checkActualBlockingStatesToCancel(changedBaseEntitlement, cancelledAddOnEntitlement, baseEffectiveEOTCancellationOrChangeDateTime, false);
        // Verify also the blocking states API doesn't add too many events (now on disk)
        checkBlockingStatesDAO(changedBaseEntitlement, cancelledAddOnEntitlement, baseEffectiveCancellationOrChangeDate, false);
    }

    @Test(groups = "slow", description = "Verify we don't mix add-ons for EOT changes")
    public void testChangePlanEOTWith2AddOns() throws Exception {
        // Add a second ADD_ON (Laser-Scope is available, not included)
        testListener.pushExpectedEvents(NextEvent.CREATE);
        final PlanPhaseSpecifier secondAddOnSpec = new PlanPhaseSpecifier("Laser-Scope", ProductCategory.ADD_ON, BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, null);
        final DefaultEntitlement secondAddOnEntitlement = (DefaultEntitlement) entitlementApi.addEntitlement(baseEntitlement.getBundleId(), secondAddOnSpec, clock.getUTCToday(), callContext);
        assertListenerStatus();

        // Change plan EOT to Assault-Rifle (Telescopic-Scope is included)
        final DefaultEntitlement changedBaseEntitlement = (DefaultEntitlement) baseEntitlement.changePlanWithDate("Assault-Rifle", BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, new LocalDate(2013, 10, 7), callContext);
        // No blocking event (EOT)
        assertListenerStatus();

        // Verify the blocking states DAO adds events not on disk for the first add-on...
        checkBlockingStatesDAO(changedBaseEntitlement, addOnEntitlement, baseEffectiveCancellationOrChangeDate, false);
        // ...but not for the second one
        final List<BlockingState> blockingStatesForSecondAddOn = blockingStateDao.getBlockingAll(secondAddOnEntitlement.getId(), BlockingStateType.SUBSCRIPTION, internalCallContext);
        Assert.assertEquals(blockingStatesForSecondAddOn.size(), 0);
    }

    @Test(groups = "slow", description = "Verify add-ons blocking states are added for IMM change plans")
    public void testChangePlanIMM() throws Exception {
        // Approximate check, as the blocking state check (checkBlockingStatesDAO) could be a bit off
        final DateTime changeDateTime = clock.getUTCNow();
        final LocalDate changeDate = clock.getUTCToday();

        // Change plan IMM (upgrade) to Assault-Rifle (Telescopic-Scope is included)
        testListener.pushExpectedEvents(NextEvent.CHANGE, NextEvent.CANCEL, NextEvent.BLOCK);
        final DefaultEntitlement changedBaseEntitlement = (DefaultEntitlement) baseEntitlement.changePlan("Assault-Rifle", BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, callContext);
        assertListenerStatus();

        // Refresh the add-on state
        final DefaultEntitlement cancelledAddOnEntitlement = (DefaultEntitlement) entitlementApi.getEntitlementForId(addOnEntitlement.getId(), callContext);

        // Verify we compute the right blocking states for the "read" path...
        checkFutureBlockingStatesToCancel(changedBaseEntitlement, null, null);
        checkFutureBlockingStatesToCancel(cancelledAddOnEntitlement, null, null);
        checkFutureBlockingStatesToCancel(changedBaseEntitlement, cancelledAddOnEntitlement, null);
        // ...and for the "write" path (which has been exercised in the change call above).
        checkActualBlockingStatesToCancel(changedBaseEntitlement, cancelledAddOnEntitlement, changeDateTime, true);
        // Verify also the blocking states DAO doesn't add too many events (all on disk)
        checkBlockingStatesDAO(changedBaseEntitlement, cancelledAddOnEntitlement, changeDate, false);

        clock.addDays(30);
        // No new event
        assertListenerStatus();

        checkFutureBlockingStatesToCancel(changedBaseEntitlement, null, null);
        checkFutureBlockingStatesToCancel(cancelledAddOnEntitlement, null, null);
        checkFutureBlockingStatesToCancel(changedBaseEntitlement, cancelledAddOnEntitlement, null);
        checkActualBlockingStatesToCancel(changedBaseEntitlement, cancelledAddOnEntitlement, changeDateTime, true);
        checkBlockingStatesDAO(changedBaseEntitlement, cancelledAddOnEntitlement, changeDate, false);
    }

    // Test the "read" path
    private void checkFutureBlockingStatesToCancel(final DefaultEntitlement baseEntitlement, @Nullable final DefaultEntitlement addOnEntitlement, @Nullable final DateTime effectiveCancellationDateTime) throws EntitlementApiException {
        final Collection<BlockingState> blockingStatesForCancellation = computeFutureBlockingStatesForAssociatedAddons(baseEntitlement);
        if (addOnEntitlement == null || effectiveCancellationDateTime == null) {
            Assert.assertEquals(blockingStatesForCancellation.size(), 0);
        } else {
            Assert.assertEquals(blockingStatesForCancellation.size(), 1);
            final BlockingState blockingState = blockingStatesForCancellation.iterator().next();
            Assert.assertEquals(blockingState.getBlockedId(), addOnEntitlement.getId());
            Assert.assertEquals(blockingState.getEffectiveDate(), effectiveCancellationDateTime);
            Assert.assertEquals(blockingState.getType(), BlockingStateType.SUBSCRIPTION);
            Assert.assertEquals(blockingState.getService(), EntitlementService.ENTITLEMENT_SERVICE_NAME);
            Assert.assertEquals(blockingState.getStateName(), DefaultEntitlementApi.ENT_STATE_CANCELLED);
        }
    }

    // Test the "write" path
    private void checkActualBlockingStatesToCancel(final DefaultEntitlement baseEntitlement, final DefaultEntitlement addOnEntitlement, @Nullable final DateTime effectiveCancellationDateTime, final boolean approximateDateCheck) throws EntitlementApiException {
        final Collection<BlockingState> blockingStatesForCancellation = computeBlockingStatesForAssociatedAddons(baseEntitlement, effectiveCancellationDateTime);
        if (effectiveCancellationDateTime == null) {
            Assert.assertEquals(blockingStatesForCancellation.size(), 0);
        } else {
            Assert.assertEquals(blockingStatesForCancellation.size(), 1);
            final BlockingState blockingState = blockingStatesForCancellation.iterator().next();
            Assert.assertEquals(blockingState.getBlockedId(), addOnEntitlement.getId());
            if (approximateDateCheck) {
                Assert.assertEquals(blockingState.getEffectiveDate().toLocalDate(), effectiveCancellationDateTime.toLocalDate());
                Assert.assertEquals(blockingState.getEffectiveDate().getMinuteOfDay(), effectiveCancellationDateTime.getMinuteOfDay());
            } else {
                Assert.assertEquals(blockingState.getEffectiveDate(), effectiveCancellationDateTime);
            }
            Assert.assertEquals(blockingState.getType(), BlockingStateType.SUBSCRIPTION);
            Assert.assertEquals(blockingState.getService(), EntitlementService.ENTITLEMENT_SERVICE_NAME);
            Assert.assertEquals(blockingState.getStateName(), DefaultEntitlementApi.ENT_STATE_CANCELLED);
        }
    }

    // Test the DAO
    private void checkBlockingStatesDAO(final DefaultEntitlement baseEntitlement, final DefaultEntitlement addOnEntitlement, final LocalDate effectiveCancellationDate, final boolean isBaseCancelled) {
        checkBlockingStatesDAO(baseEntitlement, addOnEntitlement, effectiveCancellationDate, effectiveCancellationDate, isBaseCancelled);
    }

    // Test the DAO
    private void checkBlockingStatesDAO(final DefaultEntitlement baseEntitlement, final DefaultEntitlement addOnEntitlement, final LocalDate effectiveBaseCancellationDate, final LocalDate effectiveAddOnCancellationDate, final boolean isBaseCancelled) {
        final List<BlockingState> blockingStatesForBaseEntitlement = blockingStateDao.getBlockingAll(baseEntitlement.getId(), BlockingStateType.SUBSCRIPTION, internalCallContext);
        Assert.assertEquals(blockingStatesForBaseEntitlement.size(), isBaseCancelled ? 1 : 0);
        if (isBaseCancelled) {
            Assert.assertEquals(blockingStatesForBaseEntitlement.get(0).getBlockedId(), baseEntitlement.getId());
            Assert.assertEquals(blockingStatesForBaseEntitlement.get(0).getEffectiveDate().toLocalDate(), effectiveBaseCancellationDate);
            Assert.assertEquals(blockingStatesForBaseEntitlement.get(0).getType(), BlockingStateType.SUBSCRIPTION);
            Assert.assertEquals(blockingStatesForBaseEntitlement.get(0).getService(), EntitlementService.ENTITLEMENT_SERVICE_NAME);
            Assert.assertEquals(blockingStatesForBaseEntitlement.get(0).getStateName(), DefaultEntitlementApi.ENT_STATE_CANCELLED);
        }

        final List<BlockingState> blockingStatesForAddOn = blockingStateDao.getBlockingAll(addOnEntitlement.getId(), BlockingStateType.SUBSCRIPTION, internalCallContext);
        Assert.assertEquals(blockingStatesForAddOn.size(), 1);
        Assert.assertEquals(blockingStatesForAddOn.get(0).getBlockedId(), addOnEntitlement.getId());
        Assert.assertEquals(blockingStatesForAddOn.get(0).getEffectiveDate().toLocalDate(), effectiveAddOnCancellationDate);
        Assert.assertEquals(blockingStatesForAddOn.get(0).getType(), BlockingStateType.SUBSCRIPTION);
        Assert.assertEquals(blockingStatesForAddOn.get(0).getService(), EntitlementService.ENTITLEMENT_SERVICE_NAME);
        Assert.assertEquals(blockingStatesForAddOn.get(0).getStateName(), DefaultEntitlementApi.ENT_STATE_CANCELLED);
    }

    private Collection<BlockingState> computeFutureBlockingStatesForAssociatedAddons(final DefaultEntitlement baseEntitlement) throws EntitlementApiException {
        final EventsStream eventsStream = eventsStreamBuilder.buildForEntitlement(baseEntitlement.getId(), callContext);
        return eventsStream.computeAddonsBlockingStatesForFutureSubscriptionBaseEvents();
    }

    private Collection<BlockingState> computeBlockingStatesForAssociatedAddons(final DefaultEntitlement baseEntitlement, final DateTime effectiveDate) throws EntitlementApiException {
        final EventsStream eventsStream = eventsStreamBuilder.buildForEntitlement(baseEntitlement.getId(), callContext);
        return eventsStream.computeAddonsBlockingStatesForNextSubscriptionBaseEvent(effectiveDate);
    }
}
