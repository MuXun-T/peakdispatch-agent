local offerId = ARGV[1]
local claimToken = ARGV[2]
local now = tonumber(ARGV[3])
local due = redis.call('ZSCORE', KEYS[1], offerId)
local claimExpiry = redis.call('ZSCORE', KEYS[2], offerId)
local payload = redis.call('HGET', KEYS[3], offerId)
if due and not payload then redis.call('ZREM', KEYS[1], offerId) end
if claimExpiry and tonumber(claimExpiry) <= now and not payload then
    redis.call('ZREM', KEYS[2], offerId)
    redis.call('HDEL', KEYS[4], offerId)
end
if due and payload and tonumber(due) <= now and (not claimExpiry or tonumber(claimExpiry) <= now) then
    redis.call('ZREM', KEYS[1], offerId)
    redis.call('ZADD', KEYS[2], now + tonumber(ARGV[4]), offerId)
    redis.call('HSET', KEYS[4], offerId, claimToken)
    return 1
end
if claimExpiry and tonumber(claimExpiry) <= now and payload then
    redis.call('ZADD', KEYS[2], now + tonumber(ARGV[4]), offerId)
    redis.call('HSET', KEYS[4], offerId, claimToken)
    return 1
end
return 0
