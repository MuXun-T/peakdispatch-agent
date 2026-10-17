local streamType = redis.call('TYPE', KEYS[1]).ok
if streamType ~= 'none' and streamType ~= 'stream' then
    return redis.error_reply('consumer DLQ target must be a stream')
end
local dedupeType = redis.call('TYPE', KEYS[2]).ok
if dedupeType ~= 'none' and dedupeType ~= 'hash' then
    return redis.error_reply('consumer DLQ dedupe key must be a hash')
end
local existing = redis.call('HGET', KEYS[2], ARGV[1])
if existing then return existing end
local fields = {}
for index = 2, #ARGV do
    fields[#fields + 1] = ARGV[index]
end
local streamId = redis.call('XADD', KEYS[1], '*', unpack(fields))
local marked = redis.pcall('HSET', KEYS[2], ARGV[1], streamId)
if type(marked) == 'table' and marked.err then
    local removed = redis.pcall('XDEL', KEYS[1], streamId)
    if type(removed) == 'table' and removed.err then
        return redis.error_reply(marked.err .. '; failed to remove unindexed DLQ entry: ' .. removed.err)
    end
    return redis.error_reply(marked.err)
end
return streamId
