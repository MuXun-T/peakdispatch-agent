local offerId = ARGV[1]
local dueAt = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local claimExpiry = redis.call('ZSCORE', KEYS[2], offerId)
if claimExpiry and tonumber(claimExpiry) > now then
    return 0
end
if claimExpiry then
    redis.call('ZREM', KEYS[2], offerId)
    redis.call('HDEL', KEYS[4], offerId)
end
redis.call('HSET', KEYS[3], offerId, dueAt)
redis.call('ZADD', KEYS[1], dueAt, offerId)
return 1
