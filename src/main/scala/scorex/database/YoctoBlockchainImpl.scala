package scorex.database

import java.io.{File, FileOutputStream}
import java.nio.ByteBuffer

import com.yandex.yoctodb.DatabaseFormat
import com.yandex.yoctodb.immutable.Database
import com.yandex.yoctodb.mutable.DocumentBuilder
import com.yandex.yoctodb.query.QueryBuilder._
import com.yandex.yoctodb.query.{DocumentProcessor, QueryBuilder}
import com.yandex.yoctodb.util.UnsignedByteArrays
import scorex.account.Account
import scorex.block.Block
import scorex.transaction._
import settings.Settings

import scala.collection.JavaConversions._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable


class YoctoBlockchainImpl extends BlockChain {

  private val signaturesIndex = TrieMap[Int, Array[Byte]]()
  private val blocksIndex = TrieMap[Int, Block]()

  private def feeTransaction(block: Block): FeeTransaction =
    FeeTransaction(block.generator, block.transactions.map(_.fee).sum)

  override def appendBlock(block: Block): BlockChain = {
    val dbBuilder = DatabaseFormat.getCurrent.newDatabaseBuilder()

    val blockTransactions = block.transactions

    blockTransactions.foreach { tx =>
      val txPreDocument = DatabaseFormat.getCurrent.newDocumentBuilder()

      val txDocument = tx match {
        case ptx: PaymentTransaction =>
          txPreDocument.withField("account", tx.getCreator().get.address, DocumentBuilder.IndexOption.FILTERABLE)
            .withField("account", ptx.recipient.address, DocumentBuilder.IndexOption.FILTERABLE)
            .withPayload(ptx.toBytes())

        case gtx: GenesisTransaction =>
          txPreDocument.withField("account", gtx.recipient.address, DocumentBuilder.IndexOption.FILTERABLE)
            .withPayload(gtx.toBytes())

        case _ => throw new RuntimeException(s"Serialization not implemented for $tx")
      }

      dbBuilder.merge(txDocument)
    }

    val feeTx = feeTransaction(block)
    val feeDoc = DatabaseFormat.getCurrent.newDocumentBuilder()
      .withField("account", feeTx.recipient.address, DocumentBuilder.IndexOption.FILTERABLE)
      .withPayload(feeTx.toBytes())
    dbBuilder.merge(feeDoc)

    val h = height() + 1
    val os = new FileOutputStream(filename(h))
    dbBuilder.buildWritable().writeTo(os)
    signaturesIndex += h -> block.signature
    blocksIndex += h -> block
    this
  }.ensuring(_ => signaturesIndex.size == blocksIndex.size)

  override def heightOf(block: Block): Option[Int] = signaturesIndex.find(_._2.sameElements(block.signature)).map(_._1)

  override def blockAt(height: Int): Option[Block] = blocksIndex.get(height)

  override def contains(block: Block): Boolean = contains(block.signature)

  override def contains(signature:Array[Byte]): Boolean = signaturesIndex.exists(_._2.sameElements(signature))

  override def accountTransactions(account: Account): Seq[Transaction] = {
    val chainDb = compositeDb()
    val q1 = select().where(QueryBuilder.eq("account", UnsignedByteArrays.from(account.address)))

    val seq = mutable.Buffer[PreTransaction]()

    chainDb.execute(q1, new DocumentProcessor {
      override def process(i: Int, database: Database): Boolean = {
        seq += transactionFromByteBuffer(database.getDocument(i))
        true
      }
    })

    seq.flatMap {
      case t: Transaction => Some(t)
      case _ => None
    }.toSeq
  }

  private def compositeDb() = {
    val dbs = (1 to height()).toSeq.map { h =>
      DatabaseFormat.getCurrent
        .getDatabaseReader
        .from(new File(filename(h)), false)
    }
    DatabaseFormat.getCurrent.getDatabaseReader.composite(dbs)
  }

  override def height(): Int = signaturesIndex.size

  private def filename(height: Int) = Settings.dataDir + s"/block-${height + 1}"

  private def transactionFromByteBuffer(bb: ByteBuffer): PreTransaction = {
    val ba = new Array[Byte](bb.remaining())
    bb.get(ba)
    PreTransaction.fromBytes(ba)
  }

  //todo: fromHeight & confirmations parameters ignored now
  override def balance(address: String, fromHeight: Int, confirmations: Int): BigDecimal = {
    val chainDb = compositeDb()

    val q1 = select().where(QueryBuilder.eq("account", UnsignedByteArrays.from(address)))

    //todo: concurrency problems with mutable.Buffer?
    val seq = mutable.Buffer[BigDecimal]()

    chainDb.execute(q1, new DocumentProcessor {
      override def process(i: Int, database: Database): Boolean = {
        val tx = transactionFromByteBuffer(database.getDocument(i))
        seq += tx.getAmount(new Account(address))
        true
      }
    })

    seq.sum
  }

  override def heightOf(blockSignature: Array[Byte]): Option[Int] =
    signaturesIndex.find(_._2.sameElements(blockSignature)).map(_._1)

  override def blockByHeader(signature: Array[Byte]): Option[Block] =
    signaturesIndex.find(_._2.sameElements(signature)).map(_._1).map(h => blocksIndex(h))

  //todo: implement
  override def confirmations(tx: Transaction): Option[Int] = ???

  override def discardBlock(): BlockChain = {
    require(height() > 1, "Chain is empty or contains genesis block only")
    val key = height()
    signaturesIndex -= key
    blocksIndex -= key
    new File(filename(key)).delete()
    this
  }.ensuring(_ => signaturesIndex.size == blocksIndex.size)

  //todo: implement
  override def child(block: Block): Option[Block] = ???

  //todo: implement
  override def generatedBy(account: Account): Seq[Block] = ???
}