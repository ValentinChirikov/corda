package net.corda.networkcloner.test

import net.corda.core.cloning.IdentitySpace
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.Party
import net.corda.core.schemas.PersistentStateRef
import net.corda.networkcloner.impl.IdentitySpaceImpl
import net.corda.networkcloner.impl.NodesDirPartyRepository
import net.corda.node.services.vault.VaultSchemaV1
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IdentityTests : TestSupport() {

    @Test
    fun `Identities are correctly created`() {
        val identities = getIdentitySpace("s1").getIdentities()
        assertEquals(3, identities.size)
        identities.forEach {
            assertEquals(it.sourceParty.name, it.destinationPartyAndPrivateKey.party.name)
            assertNotEquals(it.sourceParty.owningKey, it.destinationPartyAndPrivateKey.party.owningKey)
        }
    }

    @Test
    fun `Db entries containing parties can be read and written`() {
        val identitySpace = getIdentitySpace("s1")
        val sourceDb = getNodeDatabase("s1", "source", "client", identitySpace::getSourcePartyFromX500Name, identitySpace::getSourcePartyFromAnonymous)
        val sourceData = sourceDb.readCoreCordaData()

        assertEquals(2, sourceData.persistentParties.size)
        listOf(clientX500Name, operatorX500Name).forEach { expectedX500Name ->
            assertNotNull(sourceData.persistentParties.map { it.x500Name as Party }.find { it.name == expectedX500Name })
        }

        identitySpace.getIdentities().map { it.sourceParty }.filterNot { it.name.toString().contains("notary",true) }.forEach { expectedSourceParty ->
            assertNotNull(sourceData.persistentParties.find { it.x500Name?.owningKey == expectedSourceParty.owningKey })
        }

        val tempSnapshot = copyAndGetSnapshotDirectory("s1").first
        val destinationDb = getNodeDatabase(tempSnapshot,"destination", "client", identitySpace::getDestinationPartyFromX500Name, identitySpace::getDestinationPartyFromAnonymous)
        val migratedPersistentParties = sourceData.persistentParties.map { VaultSchemaV1.PersistentParty(PersistentStateRef(StateRef(SecureHash.allOnesHash,0)), identitySpace.findDestinationForSourceParty(it.x500Name!!)) }
        val migratedVaultStates = sourceData.vaultStates.map { VaultSchemaV1.VaultStates(notary = identitySpace.findDestinationForSourceParty(it.notary) as Party, contractStateClassName = it.contractStateClassName, stateStatus = it.stateStatus, recordedTime = it.recordedTime, consumedTime = it.consumedTime, lockId = it.lockId, relevancyStatus = it.relevancyStatus, lockUpdateTime = it.lockUpdateTime, constraintType = it.constraintType, constraintData = it.constraintData).apply { stateRef = PersistentStateRef(StateRef(SecureHash.allOnesHash, 1)) } }
        destinationDb.writeCoreCordaData(sourceData.copy(persistentParties = migratedPersistentParties, vaultStates = migratedVaultStates))
        val destinationData = destinationDb.readCoreCordaData()

        assertEquals(2, destinationData.persistentParties.size)
        assertEquals(1, destinationData.vaultStates.size)
        listOf(clientX500Name, operatorX500Name).forEach { expectedX500Name ->
            assertNotNull(destinationData.persistentParties.map { it.x500Name as Party }.find { it.name == expectedX500Name })
        }

        identitySpace.getIdentities().map { it.destinationPartyAndPrivateKey.party }.filterNot { it.name.toString().contains("notary",true) }.forEach { expectedDestinationParty ->
            assertNotNull(destinationData.persistentParties.find { it.x500Name?.owningKey == expectedDestinationParty.owningKey })
        }

        val expectedNotary = identitySpace.getDestinationPartyFromX500Name(notaryX500Name)
        assertNotNull(expectedNotary)
        assertEquals(expectedNotary, destinationData.vaultStates.single().notary)
    }

    private fun getIdentitySpace(snapshot : String) : IdentitySpace {
        val sourceNodesDirectory = File(getSnapshotDirectory(snapshot), "source")
        val sourcePartyRepository = NodesDirPartyRepository(sourceNodesDirectory)

        val destinationNodesDirectory = File(getSnapshotDirectory(snapshot), "destination")
        val destinationPartyRepository = NodesDirPartyRepository(destinationNodesDirectory)

        return IdentitySpaceImpl(sourcePartyRepository, destinationPartyRepository)
    }

}