package com.facebook.presto.operator;

import com.facebook.presto.block.Block;
import com.facebook.presto.block.BlockBuilder;
import com.facebook.presto.block.BlockCursor;
import com.facebook.presto.block.uncompressed.UncompressedBlock;
import com.facebook.presto.tuple.TupleInfo;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class HashSemiJoinOperator
        implements Operator
{
    private final Operator probeSource;
    private final int probeJoinChannel;
    private final List<TupleInfo> tupleInfos;
    private final SourceSetProvider sourceSetProvider;

    public HashSemiJoinOperator(Operator probeSource, int probeJoinChannel, SourceSetProvider sourceSetProvider)
    {
        this.probeSource = checkNotNull(probeSource, "probeSource is null");
        checkArgument(probeJoinChannel >= 0, "probeJoinChannel is negative");
        this.probeJoinChannel = probeJoinChannel;
        this.sourceSetProvider = checkNotNull(sourceSetProvider, "sourceSetProvider is null");

        this.tupleInfos = ImmutableList.<TupleInfo>builder()
                .addAll(probeSource.getTupleInfos())
                .add(TupleInfo.SINGLE_BOOLEAN)
                .build();
    }

    @Override
    public int getChannelCount()
    {
        return tupleInfos.size();
    }

    @Override
    public List<TupleInfo> getTupleInfos()
    {
        return tupleInfos;
    }

    @Override
    public PageIterator iterator(OperatorStats operatorStats)
    {
        checkNotNull(operatorStats, "operatorStats is null");
        return new SemiJoinIterator(tupleInfos, probeSource, probeJoinChannel, sourceSetProvider, operatorStats);
    }

    private static class SemiJoinIterator
            extends AbstractPageIterator
    {
        private final PageIterator probeIterator;
        private final int probeJoinChannel;
        private final SourceSetProvider sourceSetProvider;
        private final OperatorStats operatorStats;
        private final int probeJoinChannelFields;

        private ChannelSet channelSet;

        private SemiJoinIterator(List<TupleInfo> tupleInfos, Operator probeSource, int probeJoinChannel, SourceSetProvider sourceSetProvider, OperatorStats operatorStats)
        {
            super(tupleInfos);

            this.sourceSetProvider = sourceSetProvider;
            this.operatorStats = operatorStats;

            this.probeIterator = probeSource.iterator(operatorStats);
            this.probeJoinChannel = probeJoinChannel;

            this.probeJoinChannelFields = tupleInfos.get(probeJoinChannel).getFieldCount();
        }

        protected Page computeNext()
        {
            if (channelSet == null) {
                channelSet = sourceSetProvider.get();
            }

            if (operatorStats.isDone() || !probeIterator.hasNext()) {
                return endOfData();
            }

            // Fetch next page
            Page page = probeIterator.next();
            UncompressedBlock probeJoinBlock = (UncompressedBlock) page.getBlock(probeJoinChannel);
            BlockCursor probeJoinCursor = probeJoinBlock.cursor();

            // Set strategy to use probe block
            channelSet.setLookupSlice(probeJoinBlock.getSlice());

            BlockBuilder blockBuilder = new BlockBuilder(TupleInfo.SINGLE_BOOLEAN);
            for (int position = 0; position < page.getPositionCount(); position++) {
                checkState(probeJoinCursor.advanceNextPosition());
                if (tupleContainsNull(probeJoinCursor)) {
                    blockBuilder.appendNull();
                }
                else {
                    boolean contains = channelSet.contains(probeJoinCursor);
                    if (!contains && channelSet.containsNull()) {
                        blockBuilder.appendNull();
                    }
                    else {
                        blockBuilder.append(contains);
                    }
                }
            }

            Block[] sourceBlocks = page.getBlocks();
            return new Page(ImmutableList.<Block>builder()
                    .add(sourceBlocks)
                    .add(blockBuilder.build())
                    .build()
                    .toArray(sourceBlocks));
        }

        private boolean tupleContainsNull(BlockCursor cursor)
        {
            boolean containsNull = false;
            for (int i = 0; i < probeJoinChannelFields; i++) {
                containsNull |= cursor.isNull(i);
            }
            return containsNull;
        }

        @Override
        protected void doClose()
        {
            sourceSetProvider.close();
            probeIterator.close();
        }
    }
}