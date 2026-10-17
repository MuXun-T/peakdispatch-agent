local offerId = ARGV[1]
local claimToken = ARGV[2]
local dueAt = tonumber(ARGV[3])
local token = redis.call('HGET', KEYS[4], offerId)
if not token then return 0 end
if token ~= claimToken then return -1 end
redis.call('ZREM', KEYS[2], offerId)
redis.call('HDEL', KEYS[4], offerId)
redis.call('HSET', KEYS[3], offerId, dueAt)
redis.call('ZADD', KEYS[1], dueAt, offerId)
return 1
