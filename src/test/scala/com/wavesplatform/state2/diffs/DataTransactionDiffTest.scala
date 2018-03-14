package com.wavesplatform.state2.diffs

import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.state2.{BinaryDataEntry, BooleanDataEntry, DataEntry, IntegerDataEntry}
import com.wavesplatform.{NoShrink, TransactionGen, WithDB}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}
import scorex.account.PrivateKeyAccount
import scorex.lagonaki.mocks.TestBlock.{create => block}
import scorex.settings.TestFunctionalitySettings
import scorex.transaction.{DataTransaction, GenesisTransaction, Proofs}

class DataTransactionDiffTest extends PropSpec with PropertyChecks with Matchers with TransactionGen with NoShrink with WithDB {

  val fs = TestFunctionalitySettings.Enabled.copy(preActivatedFeatures = Map(BlockchainFeatures.DataTransaction.id -> 0))

  val baseSetup: Gen[(GenesisTransaction, PrivateKeyAccount, Long)] = for {
    master <- accountGen
    ts <- positiveLongGen
    genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).right.get
  } yield (genesis, master, ts)

  def data(sender: PrivateKeyAccount, data: List[DataEntry[_]], fee: Long, timestamp: Long): DataTransaction =
    DataTransaction.selfSigned(Proofs.Version, sender, data, fee, timestamp).right.get

  property("state invariants hold") {
    val setup = for {
      (genesis, master, ts) <- baseSetup

      key1 <- validAliasStringGen
      value1 <- positiveLongGen
      item1 = IntegerDataEntry(key1, value1)
      fee1 <- smallFeeGen
      dataTx1 = data(master, List(item1), fee1, ts + 10000)

      key2 <- validAliasStringGen
      value2 <- Arbitrary.arbitrary[Boolean]
      item2 = BooleanDataEntry(key2, value2)
      fee2 <- smallFeeGen
      dataTx2 = data(master, List(item2), fee2, ts + 20000)

      value3 <- positiveLongGen
      item3 = IntegerDataEntry(key1, value3)
      fee3 <- smallFeeGen
      dataTx3 = data(master, List(item3), fee3, ts + 30000)
    } yield (genesis, Seq(item1, item2, item3), Seq(dataTx1, dataTx2, dataTx3))

    forAll(setup) { case (genesisTx, items, txs) =>
      val sender = txs.head.sender
      val genesis = block(Seq(genesisTx))
      val blocks = txs.map(tx => block(Seq(tx)))

      val item1 = items.head
      assertDiffAndState(db, Seq(genesis), blocks(0), fs) { case (totalDiff, state) =>
        assertBalanceInvariant(totalDiff)
        state.accountPortfolio(sender).balance shouldBe (ENOUGH_AMT - txs(0).fee)
        state.accountData(sender).data.get(item1.key) shouldBe Some(item1)
      }

      val item2 = items(1)
      assertDiffAndState(db, Seq(genesis, blocks(0)), blocks(1), fs) { case (totalDiff, state) =>
        assertBalanceInvariant(totalDiff)
        state.accountPortfolio(sender).balance shouldBe (ENOUGH_AMT - txs.take(2).map(_.fee).sum)
        state.accountData(sender).data.get(item1.key) shouldBe Some(item1)
        state.accountData(sender).data.get(item2.key) shouldBe Some(item2)
      }

      val item3 = items(2)
      assertDiffAndState(db, Seq(genesis, blocks(0), blocks(1)), blocks(2), fs) { case (totalDiff, state) =>
        assertBalanceInvariant(totalDiff)
        state.accountPortfolio(sender).balance shouldBe (ENOUGH_AMT - txs.map(_.fee).sum)
        state.accountData(sender).data.get(item1.key) shouldBe Some(item3)
        state.accountData(sender).data.get(item2.key) shouldBe Some(item2)
      }
    }
  }

  property("cannot overspend funds") {
    val setup = for {
      (genesis, master, ts) <- baseSetup
      key <- validAliasStringGen
      value <- bytes64gen
      feeOverhead <- Gen.choose[Long](1, ENOUGH_AMT)
      dataTx = data(master, List(BinaryDataEntry(key, value)), ENOUGH_AMT + feeOverhead, ts + 10000)
    } yield (genesis, dataTx)

    forAll(setup) { case (genesis, dataTx) =>
      assertDiffEi(db, Seq(block(Seq(genesis))), block(Seq(dataTx)), fs) { blockDiffEi =>
        blockDiffEi should produce("negative waves balance")
      }
    }
  }

  property("validation fails prior to feature activation") {
    val setup = for {
      (genesis, master, ts) <- baseSetup
      fee <- smallFeeGen
      dataTx = data(master, List(), fee, ts + 10000)
    } yield (genesis, dataTx)
    val settings = TestFunctionalitySettings.Enabled.copy(preActivatedFeatures = Map(BlockchainFeatures.DataTransaction.id -> 10))

    forAll(setup) { case (genesis, data) =>
      assertDiffEi(db, Seq(block(Seq(genesis))), block(Seq(data)), settings) { blockDiffEi =>
        blockDiffEi should produce("DataTransaction transaction has not been activated")
      }
    }
  }
}
